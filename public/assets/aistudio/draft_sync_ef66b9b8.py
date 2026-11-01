"""Safe explicit draft submission; no automatic financial postings."""
import json
import os
import ssl
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
from network_readiness import list_drafts, mark_server_result


def submit_draft(db_path, branch_id, draft_id, server_url, token=None):
    """Send unchanged UUID/payload. On ambiguous network failures keep local draft for retry."""
    parts = urlsplit(server_url)
    if not (parts.scheme == 'https' or (parts.scheme == 'http' and parts.hostname in ('127.0.0.1', 'localhost'))):
        raise ValueError('استخدم HTTPS أو localhost للتجربة فقط')
    if not parts.hostname or parts.username or parts.password or parts.query or parts.fragment or parts.path not in ('', '/'):
        raise ValueError('عنوان السيرفر غير صالح')
    token = token or os.getenv('PMP_TOKEN_' + branch_id.replace('-', '_'), '')
    if not token:
        raise ValueError('رمز الفرع غير مضبوط في متغيرات البيئة')
    drafts = [d for d in list_drafts(db_path, branch_id=branch_id, status='draft') if d['draft_id'] == draft_id]
    if not drafts:
        raise ValueError('المسودة غير موجودة أو سبق إرسالها')
    draft = drafts[0]
    body = json.dumps({key: draft[key] for key in ('draft_id', 'branch_id', 'author', 'kind', 'payload')},
                      ensure_ascii=False, allow_nan=False).encode('utf-8')
    req = Request(server_url.rstrip('/') + '/v1/drafts', data=body, method='POST', headers={
        'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json; charset=utf-8', 'Accept': 'application/json'})
    try:
        with urlopen(req, timeout=12, context=ssl.create_default_context()) as resp:
            if resp.status != 202:
                raise ValueError('رد غير متوقع من السيرفر')
            result = json.loads(resp.read(4096))
    except HTTPError as exc:
        # Keep rejected attempts as local drafts for correction, without losing data.
        message = 'السيرفر رفض الطلب (HTTP %s)' % exc.code
        if exc.code == 403:
            message += ' — تحقق من تطابق رمز الفرع في Windows وRailway؛ الاتصال الأخضر لا يختبر الرمز'
        elif exc.code == 401:
            message += ' — رمز المصادقة مفقود أو غير مقبول'
        raise ValueError(message) from exc
    except (URLError, TimeoutError, OSError) as exc:
        raise ConnectionError('تعذر الاتصال؛ المسودة محفوظة محلياً ويمكن إعادة المحاولة') from exc
    if (result.get('draft_id') != draft_id or result.get('status') not in ('pending_review', 'approved', 'rejected')
            or result.get('posted_to_accounts') is not False):
        raise ValueError('رد السيرفر غير مطابق؛ لم تتغير المسودة المحلية')
    mark_server_result(db_path, draft_id=draft_id, branch_id=branch_id, accepted=True, server_reference=draft_id)
    return result


def submit_draft_with_login(db_path, branch_id, draft_id, server_url, username, password):
    """Opt-in central account submission; no passwords/tokens stored on disk."""
    from central_client import login, submit_owned_draft
    drafts = [d for d in list_drafts(db_path, branch_id=branch_id, status='draft') if d['draft_id'] == draft_id]
    if not drafts:
        raise ValueError('المسودة غير موجودة أو سبق إرسالها')
    draft = drafts[0]
    session = login(server_url, username, password)
    if session.get('branch_id') not in (branch_id, None) or (session.get('branch_id') is None and session.get('role') != 'manager'):
        raise ValueError('حساب المستخدم غير مصرح له بالإرسال لهذا الفرع')
    result = submit_owned_draft(server_url, session['access_token'],
                                {k: draft[k] for k in ('draft_id','branch_id','author','kind','payload')})
    if (result.get('draft_id') != draft_id or result.get('status') not in ('pending_review','approved','rejected')
            or result.get('posted_to_accounts') is not False):
        raise ValueError('رد السيرفر غير مطابق؛ المسودة محفوظة محلياً')
    mark_server_result(db_path, draft_id=draft_id, branch_id=branch_id, accepted=True, server_reference=draft_id)
    return result
