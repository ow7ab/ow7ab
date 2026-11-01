"""PMP V13 - Figma screens ("PMP Cash System - Professional Desktop Redesign").

Every functional screen in the Figma file shares one template:
    breadcrumb  ->  title + subtitle  ->  4 KPI cards  ->  quick-actions panel  ->  data table card.
This module implements that template once (``build_hub``) and supplies live data for the ten
screens (``DATA_PROVIDERS``).  It is READ-ONLY with respect to accounting data: providers only
run SELECT statements (the factory provider calls the same idempotent CREATE IF NOT EXISTS that the
factory screen itself runs).  All posting / validation logic stays in app.py.

Money is never summed across currencies: every KPI lists one line per currency.
"""
import csv
import sqlite3
from contextlib import closing
from datetime import date, datetime
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from pathlib import Path
from tkinter import filedialog, messagebox

import customtkinter as ctk

# ---- Figma tokens (identical to M3 in app.py) -------------------------------------------------
T = {'primary': '#1764D8', 'primary_hover': '#1250B0', 'gold': '#E9B75B', 'gold_dark': '#C79535',
     'surface': '#FFFFFF', 'background': '#F4F7FC', 'outline': '#E3EAF4', 'ink': '#18263D',
     'muted': '#78869B', 'soft': '#ECF2FA', 'soft_hover': '#DCE8FA', 'row_alt': '#F9FBFE',
     'green': '#159D76', 'purple': '#805AD5', 'red': '#C62828'}
FONT = 'Segoe UI'
CURRENCIES = ('SDG', 'EGP', 'USD')
VISIBLE_ROWS = 12

STATUS_COLORS = {
    'green': {'نشط', 'متوفر', 'مكتمل', 'معتمد', 'مسددة', 'متاح', 'سليمة', 'مفعّل'},
    'gold': {'للمراجعة', 'قيد المراجعة', 'منخفض', 'مسددة جزئياً', 'غير مسددة'},
    'red': {'نفد', 'ملغاة', 'مرفوض', 'معطل', 'غير سليمة'},
}


def status_color(text):
    for name, words in STATUS_COLORS.items():
        if text in words:
            return {'green': T['green'], 'gold': T['gold_dark'], 'red': T['red']}[name]
    return T['ink']


# ---- helpers -----------------------------------------------------------------------------------
def _dec(value):
    try:
        number = Decimal(str(value))
        return number if number.is_finite() else Decimal('0')
    except (InvalidOperation, TypeError, ValueError):
        return Decimal('0')


def money(value):
    number = _dec(value)
    places = max(2, -number.as_tuple().exponent)
    rendered = f'{number:,.{places}f}'
    return rendered.rstrip('0').rstrip('.') if '.' in rendered else rendered


def qty(value):
    number = _dec(value)
    rendered = f'{number:,.3f}'.rstrip('0').rstrip('.')
    return rendered or '0'


def _cents(value):
    return int((_dec(value) * 100).quantize(Decimal('1'), rounding=ROUND_HALF_UP))


def _month_start():
    return date.today().replace(day=1).isoformat()


def _lines(per_currency):
    """{'SDG': Decimal, ...} -> ['1,200 SDG', ...] (one line per currency, never summed)."""
    order = [c for c in CURRENCIES if c in per_currency] + sorted(c for c in per_currency if c not in CURRENCIES)
    lines = [f'{money(per_currency[c])} {c}' for c in order if per_currency[c] != 0]
    return lines or ['0']


def _add(bucket, currency, amount):
    bucket[currency] = bucket.get(currency, Decimal('0')) + amount


def _connect(db):
    return closing(sqlite3.connect(str(db), timeout=10))


def _kpi(title, value, note, accent):
    return {'title': title, 'value': value, 'note': note, 'accent': accent}


def _result(kpis, columns, rows, table_title, commands=None):
    return {'kpis': kpis, 'columns': columns, 'rows': rows, 'table_title': table_title,
            'commands': commands}


# ---- data providers (each returns the dict built by _result) -----------------------------------
def cash_data(ctx):
    month = _month_start()
    with _connect(ctx['db']) as con:
        boxes = [r[0] for r in con.execute('SELECT name FROM cashboxes ORDER BY id')]
        balance, last, incoming, outgoing, total = {}, {}, {}, {}, {}
        for box, cur, direction, amount, tx_date in con.execute(
                'SELECT cashbox,currency,direction,amount,tx_date FROM movements'):
            sign = 1 if direction == 'وارد' else -1
            cents = _cents(amount)
            balance[(box, cur)] = balance.get((box, cur), 0) + sign * cents
            _add(total, cur, Decimal(sign * cents) / 100)
            if tx_date >= month:
                _add(incoming if sign > 0 else outgoing, cur, Decimal(cents) / 100)
            if tx_date > last.get((box, cur), ''):
                last[(box, cur)] = tx_date
        pending = set(con.execute(
            '''SELECT m.cashbox,m.currency FROM change_requests r JOIN movements m ON m.id=r.movement_id
               WHERE r.status='pending' ''').fetchall())
    kpis = [_kpi('الرصيد المتاح', _lines(total), 'كل عملة منفصلة', T['primary']),
            _kpi('إجمالي الوارد', _lines(incoming), 'هذا الشهر', T['green']),
            _kpi('إجمالي المنصرف', _lines(outgoing), 'هذا الشهر', T['gold']),
            _kpi('الخزن النشطة', [str(len(boxes))], 'خزنة / مخزن', T['purple'])]
    rows = []
    for box in boxes:
        currencies = sorted({c for (b, c) in balance if b == box},
                            key=lambda c: CURRENCIES.index(c) if c in CURRENCIES else 99)
        if not currencies:
            rows.append((box, '—', '—', '—', 'نشط'))
        for cur in currencies:
            rows.append((box, cur, money(Decimal(balance[(box, cur)]) / 100), last.get((box, cur), '—'),
                         'للمراجعة' if (box, cur) in pending else 'نشط'))
    return _result(kpis, ['الخزنة', 'العملة', 'الرصيد', 'آخر حركة', 'الحالة'], rows, 'سجل الخزن والحسابات')


def _invoice_rows(con):
    paid = {}
    for invoice_id, amount in con.execute("SELECT invoice_id,amount FROM invoice_payments WHERE status='active'"):
        paid[invoice_id] = paid.get(invoice_id, Decimal('0')) + _dec(amount)
    result = []
    for inv_id, no, inv_date, customer, cur, total, status in con.execute(
            '''SELECT i.id,i.invoice_no,i.invoice_date,c.name,i.currency,i.total,i.status
               FROM invoices i JOIN customers c ON c.id=i.customer_id ORDER BY i.id DESC'''):
        total_d, paid_d = _dec(total), paid.get(inv_id, Decimal('0'))
        if status == 'cancelled':
            label = 'ملغاة'
        elif paid_d >= total_d:
            label = 'مسددة'
        elif paid_d > 0:
            label = 'مسددة جزئياً'
        else:
            label = 'غير مسددة'
        result.append((inv_id, no, inv_date, customer, cur, total_d, paid_d, label))
    return result


def sales_data(ctx):
    month = _month_start()
    with _connect(ctx['db']) as con:
        invoices = _invoice_rows(con)
        collected = {}
        for cur, amount in con.execute(
                '''SELECT i.currency,p.amount FROM invoice_payments p JOIN invoices i ON i.id=p.invoice_id
                   WHERE p.status='active' AND i.status='active' AND p.pay_date>=?''', (month,)):
            _add(collected, cur, _dec(amount))
    sales, remaining, open_count = {}, {}, 0
    for _id, _no, inv_date, _c, cur, total, paid, label in invoices:
        if label == 'ملغاة':
            continue
        if inv_date >= month:
            _add(sales, cur, total)
        if total - paid > 0:
            _add(remaining, cur, total - paid)
            open_count += 1
    kpis = [_kpi('قيمة المبيعات', _lines(sales), 'هذا الشهر', T['primary']),
            _kpi('المحصّل', _lines(collected), 'هذا الشهر', T['green']),
            _kpi('المتبقي', _lines(remaining), 'على كل الفواتير', T['gold']),
            _kpi('الفواتير المفتوحة', [str(open_count)], 'غير مسددة بالكامل', T['purple'])]
    rows = [(no, customer, f'{money(total)} {cur}', f'{money(paid)} {cur}', label)
            for _i, no, _d, customer, cur, total, paid, label in invoices]
    return _result(kpis, ['رقم الفاتورة', 'العميل', 'الإجمالي', 'المدفوع', 'الحالة'], rows, 'سجل الفواتير والمبيعات')


def stock_data(ctx):
    month = _month_start()
    with _connect(ctx['db']) as con:
        products = con.execute(
            "SELECT id,sku,name,unit,minimum_stock FROM products WHERE active=1 ORDER BY name").fetchall()
        balance = {}
        for product_id, quantity, kind in con.execute('SELECT product_id,quantity,kind FROM stock_moves'):
            sign = 1 if kind in ('IN', 'RETURN') else -1
            balance[product_id] = balance.get(product_id, Decimal('0')) + sign * _dec(quantity)
        moves_month = con.execute('SELECT COUNT(*) FROM stock_moves WHERE substr(created_at,1,10)>=?',
                                  (month,)).fetchone()[0]
    rows, low, available = [], 0, Decimal('0')
    for product_id, sku, name, unit, minimum in products:
        amount = balance.get(product_id, Decimal('0'))
        available += max(amount, Decimal('0'))
        if amount <= 0:
            label = 'نفد'
        elif _dec(minimum) > 0 and amount <= _dec(minimum):
            label = 'منخفض'
        else:
            label = 'متوفر'
        if label != 'متوفر':
            low += 1
        rows.append((sku, name, unit, qty(amount), label))
    kpis = [_kpi('عدد الأصناف', [str(len(products))], 'أصناف فعّالة', T['primary']),
            _kpi('الكمية المتاحة', [qty(available)], 'مجموع كل المخازن', T['green']),
            _kpi('أصناف منخفضة', [str(low)], 'منخفضة أو نافدة', T['gold']),
            _kpi('حركات المخزن', [str(moves_month)], 'هذا الشهر', T['purple'])]
    return _result(kpis, ['كود الصنف', 'الصنف', 'الوحدة', 'الكمية', 'الحالة'], rows, 'أرصدة المخازن والمنتجات')


def _factory(ctx):
    import pmp_factory
    with _connect(ctx['db']) as con:
        con.execute('PRAGMA foreign_keys=ON')
        pmp_factory.init(con)
        con.commit()
        items = con.execute('SELECT id,name,unit,category FROM factory_items ORDER BY name').fetchall()
        balances = {}
        for item_id, _n, _u, category in items:
            balances[item_id] = {'raw': pmp_factory.balance(con, item_id, 'raw'),
                                 'production': pmp_factory.balance(con, item_id, 'production'),
                                 'finished': pmp_factory.balance(con, item_id, 'finished'),
                                 'empty': pmp_factory.balance(con, item_id, 'empty'),
                                 'purchased': pmp_factory.balance(con, item_id, 'purchased'),
                                 'finished_pending': pmp_factory.balance(con, item_id, 'finished_pending'),
                                 'loading': pmp_factory.balance(con, item_id, 'loading'),
                                 'can_waste': pmp_factory.balance(con, item_id, 'can_waste')}
        moves = con.execute(
            '''SELECT m.id,m.move_date,m.kind,i.name,i.unit,m.qty,COALESCE(x.name,''),m.batch,m.note
               FROM factory_moves m JOIN factory_items i ON i.id=m.item_id
               LEFT JOIN factory_machines x ON x.id=m.machine_id ORDER BY m.id DESC''').fetchall()
    return pmp_factory, items, balances, moves


def raw_data(ctx):
    month = _month_start()
    factory, items, balances, moves = _factory(ctx)
    raw_items = [i for i in items if i[3] == 'raw']
    in_store = sum((max(balances[i[0]]['raw'], Decimal('0')) for i in raw_items), Decimal('0'))
    in_production = sum((max(balances[i[0]]['production'], Decimal('0')) for i in raw_items), Decimal('0'))
    critical = sum(1 for i in raw_items if balances[i[0]]['raw'] + balances[i[0]]['production'] <= 0)
    receipts = sum(1 for m in moves if m[2] == 'RAW_RECEIPT' and m[1] >= month)
    waiting = sum((max(balances[i[0]]['purchased'], Decimal('0')) for i in raw_items), Decimal('0'))
    kpis = [_kpi('بانتظار استلام المخزن', [qty(waiting)], 'مشتريات رانيا لم تُستلم', T['primary']),
            _kpi('المتاح للإنتاج', [qty(in_production)], 'عهدة الإنتاج', T['green']),
            _kpi('المواد الحرجة', [str(critical)], 'رصيدها صفر', T['gold']),
            _kpi('وارد الخامات', [str(receipts)], 'حركات هذا الشهر', T['purple'])]
    rows = []
    for item_id, name, unit, _c in raw_items:
        store, pool = balances[item_id]['raw'], balances[item_id]['production']
        rows.append((name, unit, qty(balances[item_id]['purchased']), qty(store), qty(pool), 'نفد' if store + pool <= 0 else 'متوفر'))
    return _result(kpis, ['الخامة', 'الوحدة', 'بانتظار الاستلام', 'رصيد المخزن', 'عهدة الإنتاج', 'الحالة'], rows, 'أرصدة المواد الخام')


def production_data(ctx):
    month = _month_start()
    factory, _items, _balances, moves = _factory(ctx)
    wanted = ('ISSUE_PRODUCTION', 'MACHINE_USE', 'MACHINE_WASTE', 'RETURN_RAW', 'PRODUCTION_OUTPUT', 'CAN_WASTE')
    mine = [m for m in moves if m[2] in wanted]
    this_month = [m for m in mine if m[1] >= month]
    batches = {m[7] for m in this_month if m[7]}
    kpis = [_kpi('صرف للإنتاج', [str(sum(1 for m in this_month if m[2] == 'ISSUE_PRODUCTION'))], 'حركات هذا الشهر', T['primary']),
            _kpi('استهلاك الماكينات', [str(sum(1 for m in this_month if m[2] == 'MACHINE_USE'))], 'حركات هذا الشهر', T['green']),
            _kpi('علب منتجة / هالكة', [qty(sum((Decimal(m[5]) for m in this_month if m[2] == 'PRODUCTION_OUTPUT'), Decimal('0')))+' / '+qty(sum((Decimal(m[5]) for m in this_month if m[2] == 'CAN_WASTE'), Decimal('0')))], 'هذا الشهر', T['gold']),
            _kpi('الباتشات', [str(len(batches))], 'باتشات هذا الشهر', T['purple'])]
    rows = [(m[7] or f'#{m[0]}', m[3], f'{qty(m[5])} {m[4]}', m[6] or '—', factory.LABELS.get(m[2], m[2]))
            for m in mine]
    return _result(kpis, ['رقم الباتشة', 'الصنف', 'الكمية', 'الماكينة', 'الحركة'], rows, 'سجل الإنتاج والتعبئة')


def finished_data(ctx):
    month = _month_start()
    factory, items, balances, moves = _factory(ctx)
    finished_items = [i for i in items if i[3] == 'finished']
    ready = sum((max(balances[i[0]]['finished'], Decimal('0')) for i in finished_items), Decimal('0'))
    empties = sum((max(balances[i[0]]['empty'], Decimal('0')) for i in items if i[3] == 'empty'), Decimal('0'))
    wanted = ('FINISHED_RECEIPT', 'HANDOVER_LOADING', 'DISPATCH_CUSTOMER', 'DISPATCH_HALFA', 'LOAD_FINISHED', 'EMPTY_IN', 'EMPTY_OUT')
    mine = [m for m in moves if m[2] in wanted]
    loads = sum(1 for m in mine if m[2] in ('HANDOVER_LOADING', 'LOAD_FINISHED') and m[1] >= month)
    kpis = [_kpi('منتجات جاهزة', [str(len(finished_items))], 'أصناف منتج تام', T['primary']),
            _kpi('رصيد المنتج التام', [qty(ready)], 'جاهز للتحميل', T['green']),
            _kpi('خارج من المصنع', [str(loads)], 'تسليمات لمسؤول التحميل هذا الشهر', T['gold']),
            _kpi('براميل فارغة', [qty(empties)], 'الرصيد الحالي', T['purple'])]
    rows = [(f'FM-{m[0]}', m[3], f'{qty(m[5])} {m[4]}', m[8] or '—', factory.LABELS.get(m[2], m[2])) for m in mine]
    return _result(kpis, ['المرجع', 'المنتج', 'الكمية', 'ملاحظات / الوجهة', 'الحركة'], rows, 'المنتج التام والتحميل')


def transfers_data(ctx):
    today = date.today().isoformat()
    with _connect(ctx['db']) as con:
        transfers = con.execute(
            '''SELECT reference,tx_date,source_cashbox,destination_cashbox,source_amount,source_currency
               FROM transfers ORDER BY id DESC''').fetchall()
        requests = con.execute(
            '''SELECT id,request_type,movement_id,status,substr(created_at,1,10) FROM change_requests
               ORDER BY id DESC''').fetchall()
    count = {'pending': 0, 'approved': 0, 'rejected': 0}
    for r in requests:
        count[r[3]] = count.get(r[3], 0) + 1
    kpis = [_kpi('تحويلات اليوم', [str(sum(1 for t in transfers if t[1] == today))], 'بين الخزن والعملات', T['primary']),
            _kpi('قيد المراجعة', [str(count['pending'])], 'طلبات تصحيح / إلغاء', T['gold']),
            _kpi('معتمدة', [str(count['approved'])], 'طلبات معتمدة', T['green']),
            _kpi('مرفوضة', [str(count['rejected'])], 'طلبات مرفوضة', T['purple'])]
    labels = {'pending': 'قيد المراجعة', 'approved': 'معتمد', 'rejected': 'مرفوض'}
    merged = [(t[1], (t[0], t[2], t[3], f'{money(t[4])} {t[5]}', 'مكتمل')) for t in transfers]
    merged += [(r[4] or '', (f'CR-{r[0]}', 'طلب ' + ('تصحيح' if r[1] == 'correct' else 'إلغاء'),
                             f'حركة #{r[2]}', '—', labels.get(r[3], r[3]))) for r in requests]
    merged.sort(key=lambda pair: pair[0], reverse=True)
    return _result(kpis, ['المرجع', 'من', 'إلى', 'القيمة', 'الحالة'], [row for _d, row in merged],
                   'التحويلات وطلبات التصحيح')


def reports_data(ctx):
    month = _month_start()
    with _connect(ctx['db']) as con:
        net, sales = {}, {}
        for cur, direction, amount in con.execute(
                'SELECT currency,direction,amount FROM movements WHERE tx_date>=?', (month,)):
            _add(net, cur, (1 if direction == 'وارد' else -1) * Decimal(_cents(amount)) / 100)
        for cur, total in con.execute("SELECT currency,total FROM invoices WHERE status='active' AND invoice_date>=?",
                                      (month,)):
            _add(sales, cur, _dec(total))
        stock_value = Decimal('0')
        prices = {r[0]: _dec(r[1]) for r in con.execute('SELECT id,purchase_price FROM products')}
        balance = {}
        for product_id, quantity, kind in con.execute('SELECT product_id,quantity,kind FROM stock_moves'):
            balance[product_id] = balance.get(product_id, Decimal('0')) + (1 if kind in ('IN', 'RETURN') else -1) * _dec(quantity)
        for product_id, amount in balance.items():
            if amount > 0:
                stock_value += amount * prices.get(product_id, Decimal('0'))
    try:
        _f, _i, _b, moves = _factory(ctx)
        production = sum(1 for m in moves if m[1] >= month and m[2] in ('MACHINE_USE', 'FINISHED_RECEIPT'))
    except Exception:
        production = 0
    kpis = [_kpi('صافي التدفق', _lines(net), 'هذا الشهر', T['primary']),
            _kpi('إجمالي المبيعات', _lines(sales), 'هذا الشهر', T['green']),
            _kpi('قيمة المخزون', [money(stock_value)], 'بسعر الشراء', T['gold']),
            _kpi('إنتاج الفترة', [str(production)], 'حركات هذا الشهر', T['purple'])]
    catalog = [
        ('تقارير المبيعات', 'حسب الفترة', 'المبيعات', 'PDF / Excel', 'متاح', 'reports_v81_dialog'),
        ('كشف حساب الخزن', 'حسب الفترة', 'الخزن والحسابات', 'PDF / Excel', 'متاح', 'statement_dialog'),
        ('سجل الحركات المالية', 'كل الفترات', 'الخزن والحسابات', 'PDF / Excel', 'متاح', 'show_financial_form'),
    ]
    if ctx['user']['role'] == 'manager':
        catalog.append(('سجل التدقيق', 'كل الفترات', 'الأمان', 'عرض', 'متاح', 'show_audit'))
    allowed = ctx.get('commands')
    if allowed is not None:                      # only list reports the user may actually open
        catalog = [c for c in catalog if c[5] in allowed]
    return _result(kpis, ['التقرير', 'الفترة', 'القسم', 'الصيغة', 'الحالة'], [c[:5] for c in catalog],
                   'التقارير المتاحة', commands=[c[5] for c in catalog])


def users_data(ctx):
    role_labels = {'manager': 'مدير', 'deputy': 'نائب المدير', 'accountant': 'محاسب'}
    with _connect(ctx['db']) as con:
        users = con.execute("SELECT id,username,display_name,role,active,COALESCE(job_title,'') FROM users ORDER BY id").fetchall()
        last_login = dict(con.execute("SELECT username,MAX(created_at) FROM audit_log WHERE action='LOGIN' GROUP BY username"))
        events = con.execute('SELECT COUNT(*) FROM audit_log').fetchone()[0]
    active = sum(1 for u in users if u[4])
    kpis = [_kpi('المستخدمون', [str(len(users))], 'حسابات مسجلة', T['primary']),
            _kpi('نشط', [str(active)], 'يمكنهم الدخول', T['green']),
            _kpi('معطل', [str(len(users) - active)], 'حسابات موقوفة', T['gold']),
            _kpi('أحداث مسجلة', [str(events)], 'في سجل التدقيق', T['purple'])]
    rows = [(u[2] or u[1], role_labels.get(u[3], u[3]), u[5] or '—', (last_login.get(u[1]) or '—')[:16],
             'نشط' if u[4] else 'معطل') for u in users]
    return _result(kpis, ['المستخدم', 'الدور', 'القسم', 'آخر دخول', 'الحالة'], rows, 'المستخدمون والصلاحيات')


def settings_data(ctx):
    db = Path(ctx['db'])
    with _connect(db) as con:
        try:
            integrity = con.execute('PRAGMA quick_check').fetchone()[0]
        except sqlite3.Error:
            integrity = 'error'
        backup = con.execute("SELECT MAX(created_at) FROM audit_log WHERE action='CREATE_BACKUP'").fetchone()[0]
        restore = con.execute("SELECT MAX(created_at) FROM audit_log WHERE action='RESTORE_BACKUP'").fetchone()[0]
        forced = con.execute('SELECT COUNT(*) FROM users WHERE must_change_password=1').fetchone()[0]
        audit_events = con.execute('SELECT COUNT(*) FROM audit_log').fetchone()[0]
    healthy = integrity == 'ok'
    branch = ctx.get('branch') or {}
    server = branch.get('server_health_url') or ''
    modified = datetime.fromtimestamp(db.stat().st_mtime).strftime('%Y-%m-%d %H:%M') if db.exists() else '—'
    kpis = [_kpi('حالة قاعدة البيانات', ['سليمة' if healthy else 'تحتاج فحصاً'], 'فحص سريع عند فتح الشاشة',
                 T['primary']),
            _kpi('آخر نسخة احتياطية', [(backup or 'لا توجد')[:10]], 'من سجل التدقيق', T['green']),
            _kpi('الفروع', [branch.get('branch_name', '—')], branch.get('branch_id', ''), T['gold']),
            _kpi('إعدادات الأمان', [str(forced)], 'حسابات تنتظر تغيير كلمة المرور', T['purple'])]
    rows = [('قاعدة البيانات', db.name, 'النظام', modified, 'سليمة' if healthy else 'غير سليمة'),
            ('النسخ الاحتياطي', (backup or 'لا توجد')[:16], 'الحماية', (restore or '—')[:16], 'متاح'),
            ('الفرع الحالي', f"{branch.get('branch_name', '—')} ({branch.get('currency', '—')})", 'الفروع', '—', 'نشط'),
            ('السيرفر المركزي', 'مضبوط' if server else 'غير مضبوط (وضع محلي)', 'الاتصال', '—',
             'نشط' if server else 'للمراجعة'),
            ('سجل التدقيق', f'{audit_events} حدث', 'الأمان', '—', 'نشط'),
            ('إصدار البرنامج', ctx.get('version', '—'), 'النظام', '—', 'نشط')]
    return _result(kpis, ['الإعداد', 'القيمة', 'القسم', 'آخر تعديل', 'الحالة'], rows, 'إعدادات النظام')


DATA_PROVIDERS = {'cash': cash_data, 'sales': sales_data, 'stock': stock_data, 'raw': raw_data,
                  'production': production_data, 'finished': finished_data, 'transfers': transfers_data,
                  'reports': reports_data, 'users': users_data, 'settings': settings_data}


def load(key, ctx):
    """Run a provider; a failure becomes an empty page with an error note, never a crash."""
    try:
        return DATA_PROVIDERS[key](ctx)
    except Exception as exc:   # pragma: no cover - defensive, shown on screen
        blank = [_kpi(t, ['—'], '', a) for t, a in (('—', T['primary']), ('—', T['green']),
                                                    ('—', T['gold']), ('—', T['purple']))]
        result = _result(blank, ['—'] * 5, [], 'تعذر تحميل البيانات')
        result['error'] = f'{type(exc).__name__}: {exc}'
        return result


# ---- widgets -----------------------------------------------------------------------------------
def _card(parent, **kwargs):
    kwargs.setdefault('fg_color', T['surface'])
    kwargs.setdefault('corner_radius', 14)
    return ctk.CTkFrame(parent, **kwargs)


class Hub:
    """One Figma module page. ``reload()`` re-reads the database and repaints KPIs and table."""

    def __init__(self, parent, key, ctx, title, subtitle, actions, table_title=None, open_all=None,
                 row_commands=None):
        self.key, self.ctx = key, ctx
        self.actions = actions            # [(label, callable)]  first one is the primary (blue) button
        self.open_all = open_all          # callable for "عرض كل السجلات"
        self.row_commands = row_commands  # {command_name: callable} for clickable catalog rows
        self.data = None
        self.kpi_cards = []
        self.row_widgets = []
        self.search_var = ctk.StringVar()
        self._search_job = None
        self._paint_signature = None

        head = ctk.CTkFrame(parent, fg_color='transparent')
        head.pack(fill='x', padx=26, pady=(8, 6))
        ctk.CTkLabel(head, text=title, font=(FONT, 30, 'bold'), text_color=T['ink'], anchor='e').pack(anchor='e')
        ctk.CTkLabel(head, text=subtitle, font=(FONT, 13), text_color=T['muted'], anchor='e').pack(anchor='e', pady=(2, 0))

        self.kpi_row = ctk.CTkFrame(parent, fg_color='transparent')
        self.kpi_row.pack(fill='x', padx=21, pady=(14, 0))
        for col in range(4):
            self.kpi_row.grid_columnconfigure(col, weight=1, uniform='kpi')
        for index in range(4):                       # RTL: first KPI sits at the right
            card = _card(self.kpi_row, height=140)
            card.grid(row=0, column=3 - index, sticky='ew', padx=5)
            card.pack_propagate(False)
            card.grid_propagate(False)
            accent = ctk.CTkFrame(card, width=5, height=38, corner_radius=3, fg_color=T['primary'])
            accent.place(relx=1.0, x=-14, y=16, anchor='ne')
            title_label = ctk.CTkLabel(card, text='', width=190, font=(FONT, 13), text_color=T['muted'], anchor='e')
            title_label.place(relx=1.0, x=-30, y=14, anchor='ne')
            value_label = ctk.CTkLabel(card, text='—', width=200, font=(FONT, 26, 'bold'), text_color=T['ink'],
                                       anchor='e', justify='right')
            value_label.place(relx=1.0, x=-24, y=52, anchor='ne')
            note_label = ctk.CTkLabel(card, text='', width=190, font=(FONT, 11), text_color=T['muted'], anchor='e')
            note_label.place(relx=1.0, x=-24, rely=1.0, y=-12, anchor='se')
            self.kpi_cards.append((accent, title_label, value_label, note_label))

        panel = _card(parent)
        panel.pack(fill='x', padx=26, pady=(16, 0))
        ctk.CTkLabel(panel, text='إجراءات سريعة', font=(FONT, 16, 'bold'), text_color=T['ink'],
                     anchor='e').pack(fill='x', padx=24, pady=(14, 8))
        visible = [a for a in actions if a is not None]
        if not visible:
            ctk.CTkLabel(panel, text='لا توجد إجراءات متاحة لصلاحياتك', font=(FONT, 12),
                         text_color=T['muted'], anchor='e').pack(fill='x', padx=24, pady=(0, 16))
        for start in range(0, len(visible), 4):
            row = ctk.CTkFrame(panel, fg_color='transparent')
            row.pack(fill='x', padx=24, pady=(0, 12 if start + 4 >= len(visible) else 6))
            for offset in range(4):
                row.grid_columnconfigure(offset, weight=1, uniform='act')
            for offset, (label, command) in enumerate(visible[start:start + 4]):
                primary = (start + offset == 0)
                ctk.CTkButton(row, text=label, height=38, corner_radius=10, font=(FONT, 13, 'bold'),
                              fg_color=T['primary'] if primary else T['soft'],
                              hover_color=T['primary_hover'] if primary else T['soft_hover'],
                              text_color='#FFFFFF' if primary else T['ink'], anchor='center',
                              command=command).grid(row=0, column=3 - offset, sticky='ew', padx=6)

        table = _card(parent)
        table.pack(fill='x', padx=26, pady=(16, 22))
        top = ctk.CTkFrame(table, fg_color='transparent')
        top.pack(fill='x', padx=24, pady=(14, 8))
        self.table_title = ctk.CTkLabel(top, text=table_title or '', font=(FONT, 16, 'bold'),
                                        text_color=T['ink'], anchor='e')
        self.table_title.pack(side='right')
        self.search_entry = ctk.CTkEntry(top, textvariable=self.search_var, width=210, height=34,
                                         corner_radius=10, placeholder_text='بحث...', justify='right',
                                         border_color=T['outline'], fg_color='#F7FAFE')
        self.search_entry.pack(side='left')
        ctk.CTkButton(top, text='تصدير', width=84, height=34, corner_radius=10, font=(FONT, 12, 'bold'),
                      fg_color=T['soft'], hover_color=T['soft_hover'], text_color=T['primary'],
                      command=self.export).pack(side='left', padx=(0, 8))
        ctk.CTkButton(top, text='↻', width=36, height=34, corner_radius=10, font=(FONT, 14, 'bold'),
                      fg_color=T['soft'], hover_color=T['soft_hover'], text_color=T['primary'],
                      command=self.reload).pack(side='left', padx=(0, 8))
        self.search_var.trace_add('write', self._schedule_search)
        self.head_row = ctk.CTkFrame(table, fg_color=T['soft'], corner_radius=10, height=44)
        self.head_row.pack(fill='x', padx=19, pady=(0, 2))
        self.head_row.pack_propagate(False)
        for col in range(5):
            self.head_row.grid_columnconfigure(col, weight=1, uniform='tcol')
        self.head_labels = []
        for col in range(5):
            label = ctk.CTkLabel(self.head_row, text='', font=(FONT, 12, 'bold'), text_color=T['ink'], anchor='e')
            label.grid(row=0, column=4 - col, sticky='ew', padx=14, pady=8)
            self.head_labels.append(label)
        self.body = ctk.CTkFrame(table, fg_color='transparent')
        self.body.pack(fill='x', padx=19)
        self.footer = ctk.CTkFrame(table, fg_color='transparent')
        self.footer.pack(fill='x', padx=24, pady=(6, 14))
        self.count_label = ctk.CTkLabel(self.footer, text='', font=(FONT, 11), text_color=T['muted'], anchor='e')
        self.count_label.pack(side='right')
        if open_all:
            ctk.CTkButton(self.footer, text='عرض كل السجلات  ←', width=150, height=30, corner_radius=8,
                          font=(FONT, 12, 'bold'), fg_color='transparent', hover_color=T['soft'],
                          text_color=T['primary'], command=open_all).pack(side='left')
        self.error_label = ctk.CTkLabel(parent, text='', font=(FONT, 11), text_color=T['red'], anchor='e')
        self.error_label.pack(fill='x', padx=28)
        self.reload()

    # -- data -------------------------------------------------------------------------------
    def reload(self):
        self.data = load(self.key, self.ctx)
        for (accent, title_label, value_label, note_label), kpi in zip(self.kpi_cards, self.data['kpis']):
            accent.configure(fg_color=kpi['accent'])
            title_label.configure(text=kpi['title'])
            lines = kpi['value']
            if len(lines) == 1:
                value_label.configure(text=lines[0], font=(FONT, 26 if len(lines[0]) <= 14 else 18, 'bold'))
            else:
                value_label.configure(text='\n'.join(lines[:3]), font=(FONT, 15, 'bold'))
            note_label.configure(text=kpi['note'])
        for label, text in zip(self.head_labels, self.data['columns']):
            label.configure(text=text)
        if self.data.get('table_title'):
            self.table_title.configure(text=self.data['table_title'])
        self.error_label.configure(text=('تعذر تحميل بعض البيانات: ' + self.data['error']) if self.data.get('error') else '')
        self._paint_signature = None
        self._paint_rows()

    def _schedule_search(self, *_args):
        # Debounce input: creating dozens of CustomTkinter widgets per keystroke
        # blocks the Tk event loop and makes the whole application feel frozen.
        if self._search_job is not None:
            try:
                self.body.after_cancel(self._search_job)
            except Exception:
                pass
        self._search_job = self.body.after(240, self._run_search)

    def _run_search(self):
        self._search_job = None
        if self.body.winfo_exists():
            self._paint_rows()

    def _filtered(self):
        needle = self.search_var.get().strip().lower()
        pairs = list(enumerate(self.data['rows']))
        if needle:
            pairs = [(i, r) for i, r in pairs if any(needle in str(cell).lower() for cell in r)]
        return pairs

    def _paint_rows(self):
        if self.data is None:
            return
        # Do not tear down and recreate Tk widgets for an unchanged view.
        signature = (id(self.data), self.search_var.get().strip().casefold())
        if signature == self._paint_signature:
            return
        self._paint_signature = signature
        for widget in self.row_widgets:
            widget.destroy()
        self.row_widgets = []
        pairs = self._filtered()
        total = len(self.data['rows'])
        commands = self.data.get('commands')
        for position, (index, values) in enumerate(pairs[:VISIBLE_ROWS]):
            row = ctk.CTkFrame(self.body, fg_color=T['row_alt'] if position % 2 else T['surface'],
                               corner_radius=8, height=44)
            row.pack(fill='x', pady=1)
            row.pack_propagate(False)
            for col in range(5):
                row.grid_columnconfigure(col, weight=1, uniform='tcol')
            for col, cell in enumerate(values):
                is_status = col == 4
                label = ctk.CTkLabel(row, text=str(cell), anchor='e',
                                     font=(FONT, 12, 'bold' if is_status or col == 0 else 'normal'),
                                     text_color=status_color(str(cell)) if is_status else
                                     (T['ink'] if col == 0 else '#3B4A62'))
                label.grid(row=0, column=4 - col, sticky='ew', padx=14, pady=8)
            if commands and self.row_commands:
                action = self.row_commands.get(commands[index])
                if action:
                    for widget in (row,) + tuple(row.winfo_children()):
                        widget.configure(cursor='hand2')
                        widget.bind('<Button-1>', lambda _e, a=action: a(), add='+')
            self.row_widgets.append(row)
        if not pairs:
            empty = ctk.CTkLabel(self.body, text='لا توجد بيانات مطابقة' if total else 'لا توجد بيانات بعد',
                                 font=(FONT, 13), text_color=T['muted'], height=70)
            empty.pack(fill='x')
            self.row_widgets.append(empty)
        shown = min(len(pairs), VISIBLE_ROWS)
        self.count_label.configure(text=f'عرض {shown} من {len(pairs)}' + (f'  (الإجمالي {total})' if len(pairs) != total else ''))

    # -- export (CSV, Excel-compatible) ------------------------------------------------------------
    def export(self):
        rows = [r for _i, r in self._filtered()]
        if not rows:
            messagebox.showinfo('تصدير', 'لا توجد بيانات للتصدير')
            return
        path = filedialog.asksaveasfilename(
            defaultextension='.csv', filetypes=[('CSV', '*.csv')],
            initialfile=f"PMP_{self.key}_{date.today().isoformat()}.csv")
        if not path:
            return
        try:
            with open(path, 'w', newline='', encoding='utf-8-sig') as handle:
                writer = csv.writer(handle)
                writer.writerow(self.data['columns'])
                writer.writerows(rows)
        except OSError as exc:
            messagebox.showerror('تصدير', f'تعذر حفظ الملف: {exc}')
            return
        messagebox.showinfo('تصدير', f'تم حفظ {len(rows)} سجل')
