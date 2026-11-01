"""Opt-in HTTPS central authentication client. Does not change the current desktop UI."""
import json
import ssl
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError


def _base(url):
    parts = urlsplit(url)
    if parts.scheme != 'https' or not parts.hostname or parts.username or parts.password or parts.query or parts.fragment or parts.path not in ('', '/'):
        raise ValueError('Central API must use an HTTPS base URL')
    return url.rstrip('/')


def _request(base_url, path, body, token=None):
    headers = {'Content-Type': 'application/json', 'Accept': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = Request(_base(base_url) + path, data=json.dumps(body,ensure_ascii=False,allow_nan=False).encode('utf-8'), headers=headers, method='POST')
    try:
        with urlopen(request, timeout=15, context=ssl.create_default_context()) as response:
            return json.load(response)
    except HTTPError as exc:
        # Do not leak credentials, tokens, or server response bodies into the UI.
        if exc.code == 401:
            raise ValueError('اسم المستخدم أو كلمة المرور غير صحيحة، أو انتهت الجلسة') from exc
        if exc.code == 403:
            raise ValueError('لا توجد صلاحية لتنفيذ العملية في هذا الفرع (HTTP 403)') from exc
        raise ValueError('فشل طلب السيرفر (HTTP %s)' % exc.code) from exc
    except (URLError, TimeoutError, OSError) as exc:
        raise ConnectionError('تعذر الاتصال بالسيرفر؛ المسودة ما زالت محلية') from exc


def login(base_url, username, password):
    return _request(base_url, '/v1/auth/login', {'username': username, 'password': password})


def submit_owned_draft(base_url, access_token, draft):
    return _request(base_url, '/v1/user-drafts', draft, token=access_token)
