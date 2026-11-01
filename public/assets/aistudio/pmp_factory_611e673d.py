"""PMP V11 Egypt factory module. Local test module, no server sync."""
import sqlite3
from datetime import date
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
import customtkinter as ctk
from tkinter import ttk, messagebox

def ar(text):
    """Keep Unicode Arabic logical order; native Tk widgets handle presentation."""
    return str(text)


# ---- V16 production cycle -------------------------------------------------------------------------
# شراء خامات (رانيا) -> استلام أمين المخزن (إبراهيم) -> صرف لمهندسي الإنتاج -> استهلاك + تسجيل علب منتجة/هالكة
# -> استلام أمين مخزن المنتج التام -> تسليم لمسؤول التحميل (خروج من المصنع) -> خروج من عهدته لعميل أو لمخزن حلفا.
KINDS = ('RAW_PURCHASE','RAW_RECEIPT','ISSUE_PRODUCTION','MACHINE_USE','MACHINE_WASTE','RETURN_RAW',
         'PRODUCTION_OUTPUT','CAN_WASTE','FINISHED_RECEIPT','HANDOVER_LOADING','DISPATCH_CUSTOMER','DISPATCH_HALFA',
         'LOAD_FINISHED','EMPTY_IN','EMPTY_OUT')
LEGACY_KINDS = ('LOAD_FINISHED',)          # pre-V16 direct loading; kept for history, no longer offered in forms
LABELS = {'RAW_PURCHASE':'شراء خامات من الخارج (بانتظار استلام المخزن)',
          'RAW_RECEIPT':'استلام خامات بالمخزن','ISSUE_PRODUCTION':'صرف خامات لمهندس الإنتاج',
          'MACHINE_USE':'استهلاك ماكينة','MACHINE_WASTE':'هالك خامات بالماكينة','RETURN_RAW':'مرتجع خام للمخزن',
          'PRODUCTION_OUTPUT':'علب منتجة (منتج تام بانتظار الاستلام)','CAN_WASTE':'علب هالكة',
          'FINISHED_RECEIPT':'استلام منتج تام بالمخزن','HANDOVER_LOADING':'تسليم لمسؤول التحميل (خروج من المصنع)',
          'DISPATCH_CUSTOMER':'خروج من عهدة التحميل: بيع لعميل','DISPATCH_HALFA':'خروج من عهدة التحميل: ترحيل لمخزن حلفا',
          'LOAD_FINISHED':'تحميل منتج تام (قديم)','EMPTY_IN':'حصر براميل فارغة','EMPTY_OUT':'خروج براميل فارغة'}
CATEGORY = {'RAW_PURCHASE':'raw','RAW_RECEIPT':'raw','ISSUE_PRODUCTION':'raw','MACHINE_USE':'raw','MACHINE_WASTE':'raw',
            'RETURN_RAW':'raw','PRODUCTION_OUTPUT':'finished','CAN_WASTE':'finished','FINISHED_RECEIPT':'finished',
            'HANDOVER_LOADING':'finished','DISPATCH_CUSTOMER':'finished','DISPATCH_HALFA':'finished',
            'LOAD_FINISHED':'finished','EMPTY_IN':'empty','EMPTY_OUT':'empty'}
# job presets: which movements each job normally posts (used by the permissions screen)
JOB_PRESETS = {
    'مسؤول المشتريات': ('RAW_PURCHASE',),
    'أمين مخزن الخامات': ('RAW_RECEIPT','ISSUE_PRODUCTION','RETURN_RAW','EMPTY_IN','EMPTY_OUT'),
    'مهندس الإنتاج': ('MACHINE_USE','MACHINE_WASTE','PRODUCTION_OUTPUT','CAN_WASTE'),
    'أمين مخزن المنتج التام': ('FINISHED_RECEIPT','HANDOVER_LOADING'),
    'مسؤول التحميل': ('DISPATCH_CUSTOMER','DISPATCH_HALFA'),
}
HALFA_CASHBOX = 'خزنة مصر'
HALFA_WAREHOUSE = 'مخزن حلفا'
HALFA_CURRENCY = 'EGP'

SCHEMA = """
CREATE TABLE IF NOT EXISTS factory_roles (username TEXT PRIMARY KEY, role TEXT NOT NULL CHECK(role IN ('warehouse','production','loading','viewer')));
CREATE TABLE IF NOT EXISTS factory_permissions (username TEXT NOT NULL, kind TEXT NOT NULL, allowed INTEGER NOT NULL CHECK(allowed IN (0,1)), PRIMARY KEY(username,kind));
CREATE TABLE IF NOT EXISTS factory_items (id INTEGER PRIMARY KEY, name TEXT NOT NULL, unit TEXT NOT NULL, category TEXT NOT NULL CHECK(category IN ('raw','finished','empty')), UNIQUE(name,unit,category));
CREATE TABLE IF NOT EXISTS factory_machines (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE);
CREATE TABLE IF NOT EXISTS factory_moves (id INTEGER PRIMARY KEY, move_date TEXT NOT NULL, kind TEXT NOT NULL, item_id INTEGER NOT NULL REFERENCES factory_items(id), qty TEXT NOT NULL, machine_id INTEGER REFERENCES factory_machines(id), batch TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '', actor TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE INDEX IF NOT EXISTS idx_factory_move_item ON factory_moves(item_id,kind);
"""
ALLOWED = {'warehouse':{'RAW_RECEIPT','ISSUE_PRODUCTION','RETURN_RAW','EMPTY_IN','EMPTY_OUT'},
           'production':{'MACHINE_USE','MACHINE_WASTE','PRODUCTION_OUTPUT','CAN_WASTE'},
           'loading':{'DISPATCH_CUSTOMER','DISPATCH_HALFA'},'viewer':set()}


def init_halfa(con):
    """Halfa purchase invoices (payable in EGP on the Egypt cashbox) and their settlements. Plain execute()
    calls only, so it is safe inside an open transaction (app.setup)."""
    con.execute("""CREATE TABLE IF NOT EXISTS halfa_invoices (
        id INTEGER PRIMARY KEY, invoice_no TEXT NOT NULL UNIQUE, invoice_date TEXT NOT NULL,
        cashbox TEXT NOT NULL, currency TEXT NOT NULL, warehouse_id INTEGER NOT NULL,
        product_id INTEGER NOT NULL, quantity TEXT NOT NULL, unit_price TEXT NOT NULL, total TEXT NOT NULL,
        factory_move_id INTEGER, note TEXT NOT NULL DEFAULT '',
        status TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('active','cancelled')),
        created_by TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)""")
    con.execute("""CREATE TABLE IF NOT EXISTS halfa_settlements (
        id INTEGER PRIMARY KEY, invoice_id INTEGER NOT NULL REFERENCES halfa_invoices(id),
        transfer_reference TEXT NOT NULL, amount TEXT NOT NULL, settle_date TEXT NOT NULL,
        created_by TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)""")
    con.execute('CREATE INDEX IF NOT EXISTS idx_halfa_settle_invoice ON halfa_settlements(invoice_id)')


def init(con):
    con.executescript(SCHEMA)
    columns = {r[1] for r in con.execute('PRAGMA table_info(factory_moves)')}
    if 'counterparty' not in columns:      # supplier / recipient / customer of the movement
        con.execute("ALTER TABLE factory_moves ADD COLUMN counterparty TEXT NOT NULL DEFAULT ''")
    if 'linked' not in columns:            # 1 = settles a pending hand-over (purchase / production output)
        con.execute("ALTER TABLE factory_moves ADD COLUMN linked INTEGER NOT NULL DEFAULT 0")
    init_halfa(con)


# pool -> {kind: (sign, only_when_linked)}
POOLS = {
    'purchased': {'RAW_PURCHASE':(1,False),'RAW_RECEIPT':(-1,True)},            # bought, waiting for the store keeper
    'raw': {'RAW_RECEIPT':(1,False),'ISSUE_PRODUCTION':(-1,False),'RETURN_RAW':(1,False)},
    'production': {'ISSUE_PRODUCTION':(1,False),'MACHINE_USE':(-1,False),'MACHINE_WASTE':(-1,False),'RETURN_RAW':(-1,False)},
    'finished_pending': {'PRODUCTION_OUTPUT':(1,False),'FINISHED_RECEIPT':(-1,True)},   # produced, waiting for the keeper
    'finished': {'FINISHED_RECEIPT':(1,False),'HANDOVER_LOADING':(-1,False),'LOAD_FINISHED':(-1,False)},
    'loading': {'HANDOVER_LOADING':(1,False),'DISPATCH_CUSTOMER':(-1,False),'DISPATCH_HALFA':(-1,False)},
    'can_waste': {'CAN_WASTE':(1,False)},
    'empty': {'EMPTY_IN':(1,False),'EMPTY_OUT':(-1,False)},
}
SOURCE_POOL = {'ISSUE_PRODUCTION':'raw','MACHINE_USE':'production','MACHINE_WASTE':'production','RETURN_RAW':'production',
               'HANDOVER_LOADING':'finished','LOAD_FINISHED':'finished','DISPATCH_CUSTOMER':'loading',
               'DISPATCH_HALFA':'loading','EMPTY_OUT':'empty'}
PENDING_POOL = {'RAW_RECEIPT':'purchased','FINISHED_RECEIPT':'finished_pending'}
NEEDS_RECIPIENT = {'ISSUE_PRODUCTION':'مهندس الإنتاج المستلم','HANDOVER_LOADING':'مسؤول التحميل المستلم',
                   'DISPATCH_CUSTOMER':'العميل'}
MACHINE_KINDS = ('MACHINE_USE','MACHINE_WASTE','PRODUCTION_OUTPUT','CAN_WASTE')
WHOLE_NUMBER_KINDS = ('PRODUCTION_OUTPUT','CAN_WASTE')     # cans are counted, never fractions


def balance(con,item_id,location):
    signs=POOLS[location]
    result=Decimal('0')
    for kind,qty,linked in con.execute('SELECT kind,qty,linked FROM factory_moves WHERE item_id=?',(item_id,)):
        rule=signs.get(kind)
        if rule and (not rule[1] or linked):
            result+=Decimal(qty)*rule[0]
    return result


def can_post(con, actor, role, kind):
    if role == 'manager':
        return True
    explicit = con.execute('SELECT allowed FROM factory_permissions WHERE username=? AND kind=?', (actor,kind)).fetchone()
    return bool(explicit[0]) if explicit is not None else kind in ALLOWED.get(role,set())


def post(con,actor,role,kind,item_id,qty,machine_id=None,batch='',note='',counterparty=''):
    """Validate and insert one factory movement; returns its id. Caller wraps it in ``with con``."""
    if not can_post(con,actor,role,kind):
        raise PermissionError('ليست لديك صلاحية هذه الحركة')
    if kind not in KINDS: raise ValueError('نوع حركة غير صحيح')
    try: amount=Decimal(str(qty).strip())
    except InvalidOperation: raise ValueError('الكمية غير صحيحة')
    if not amount.is_finite() or amount<=0 or amount>Decimal('1000000000') or amount.as_tuple().exponent < -3:
        raise ValueError('الكمية يجب أن تكون موجبة وبحد أقصى 3 منازل عشرية')
    if kind in WHOLE_NUMBER_KINDS and amount != amount.to_integral_value():
        raise ValueError('عدد العلب يجب أن يكون رقماً صحيحاً')
    row=con.execute('SELECT category FROM factory_items WHERE id=?',(item_id,)).fetchone()
    if not row: raise ValueError('الصنف غير موجود')
    if row[0]!=CATEGORY[kind]: raise ValueError('نوع الصنف لا يتوافق مع الحركة')
    if kind in MACHINE_KINDS:
        if not machine_id or not con.execute('SELECT 1 FROM factory_machines WHERE id=?',(machine_id,)).fetchone():
            raise ValueError('اختار الماكينة')
        if not batch.strip(): raise ValueError('رقم الباتشة مطلوب')
    else: machine_id=None
    counterparty=str(counterparty or '').strip()[:120]
    if kind in NEEDS_RECIPIENT and not counterparty:
        raise ValueError('حدّد: '+NEEDS_RECIPIENT[kind])
    source=SOURCE_POOL.get(kind)
    if source and balance(con,item_id,source)<amount: raise ValueError('الرصيد المتاح غير كافٍ')
    linked=0
    if kind in PENDING_POOL:
        pending=balance(con,item_id,PENDING_POOL[kind])
        if pending>=amount:
            linked=1                       # settles a pending hand-over
        elif role=='manager' and pending<=0:
            linked=0                       # manager direct entry (opening balance / no hand-over recorded)
        else:
            raise ValueError(f'الكمية أكبر من المعلّق للاستلام ({pending})')
    cur=con.execute('INSERT INTO factory_moves(move_date,kind,item_id,qty,machine_id,batch,note,actor,counterparty,linked) VALUES(?,?,?,?,?,?,?,?,?,?)',
                (date.today().isoformat(),kind,item_id,str(amount),machine_id,batch.strip()[:70],note.strip()[:350],actor,counterparty,linked))
    return cur.lastrowid


def _dec2(value, label, places=2, maximum='999999999999.99'):
    try: number=Decimal(str(value).strip())
    except InvalidOperation: raise ValueError(label+' غير صحيح')
    if not number.is_finite() or number<=0 or number>Decimal(maximum) or number.as_tuple().exponent < -places:
        raise ValueError(f'{label} يجب أن يكون موجباً وبحد أقصى {places} منازل عشرية')
    return number


def dispatch_halfa(con,actor,role,item_id,qty,product_id,cartons,unit_price,note=''):
    """Move goods out of the loader's custody to Halfa and book the purchase invoice (EGP) payable on the
    Egypt cashbox, atomically (caller wraps in ``with con``). Returns (move_id, invoice_no, total)."""
    cartons=_dec2(cartons,'عدد الكراتين',3,'1000000000')
    unit_price=_dec2(unit_price,'سعر الوحدة بالجنيه')
    product=con.execute('SELECT id,name FROM products WHERE id=? AND active=1',(product_id,)).fetchone()
    if not product: raise ValueError('اختر صنف الكتالوج المرحَّل إلى حلفا')
    warehouse=con.execute('SELECT id,active FROM warehouses WHERE name=? COLLATE NOCASE',(HALFA_WAREHOUSE,)).fetchone()
    if warehouse is None:
        con.execute('INSERT INTO warehouses(name,active) VALUES(?,1)',(HALFA_WAREHOUSE,))
        warehouse=con.execute('SELECT id,active FROM warehouses WHERE name=? COLLATE NOCASE',(HALFA_WAREHOUSE,)).fetchone()
    elif not warehouse[1]:
        raise ValueError('مخزن حلفا موقوف؛ فعّله أولاً')
    total=(cartons*unit_price).quantize(Decimal('0.01'),rounding=ROUND_HALF_UP)
    if total<=0 or total>Decimal('999999999999.99'): raise ValueError('إجمالي الفاتورة غير صالح')
    number=(con.execute('SELECT COALESCE(MAX(id),0) FROM halfa_invoices').fetchone()[0])+1
    invoice_no=f'HLF-{number:04d}'
    move_id=post(con,actor,role,'DISPATCH_HALFA',item_id,qty,counterparty=HALFA_WAREHOUSE,
                 note=(note.strip()+' ' if note.strip() else '')+f'فاتورة {invoice_no}')
    today=date.today().isoformat()
    con.execute("""INSERT INTO halfa_invoices(invoice_no,invoice_date,cashbox,currency,warehouse_id,product_id,quantity,unit_price,total,factory_move_id,note,created_by)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",(invoice_no,today,HALFA_CASHBOX,HALFA_CURRENCY,warehouse[0],product[0],
                   str(cartons),str(unit_price),str(total),move_id,note.strip()[:200],actor))
    con.execute("INSERT INTO stock_moves(warehouse_id,product_id,quantity,kind,note,created_by) VALUES(?,?,?,'IN',?,?)",
                (warehouse[0],product[0],str(cartons),f'ترحيل من المصنع - فاتورة {invoice_no}',actor))
    con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
                (actor,'HALFA_DISPATCH',f'{invoice_no}: {product[1]} {cartons} كرتونة × {unit_price} = {total} {HALFA_CURRENCY}'))
    return move_id,invoice_no,total


def halfa_invoice_rows(con, only_open=False):
    """[(id,no,date,total,paid,remaining,currency,cashbox,product_name,quantity)] newest first."""
    paid={}
    for invoice_id,amount in con.execute('SELECT invoice_id,amount FROM halfa_settlements'):
        paid[invoice_id]=paid.get(invoice_id,Decimal('0'))+Decimal(amount)
    result=[]
    for r in con.execute("""SELECT i.id,i.invoice_no,i.invoice_date,i.total,i.currency,i.cashbox,p.name,i.quantity
                            FROM halfa_invoices i JOIN products p ON p.id=i.product_id WHERE i.status='active' ORDER BY i.id DESC"""):
        total=Decimal(r[3]); done=paid.get(r[0],Decimal('0')); remaining=total-done
        if only_open and remaining<=0: continue
        result.append((r[0],r[1],r[2],total,done,remaining,r[4],r[5],r[6],r[7]))
    return result


def apply_settlement(con,invoice_id,transfer_reference,amount,settle_date,actor,dest_cashbox,dest_currency,dest_amount):
    """Deduct ``amount`` of a cash transfer (already validated by the caller) from one Halfa invoice."""
    amount=_dec2(amount,'المبلغ المخصوم من فاتورة حلفا')
    row=con.execute("SELECT invoice_no,total,cashbox,currency,status FROM halfa_invoices WHERE id=?",(invoice_id,)).fetchone()
    if not row or row[4]!='active': raise ValueError('فاتورة حلفا غير موجودة أو ملغاة')
    if row[2]!=dest_cashbox: raise ValueError(f'الفاتورة مستحقة على «{row[2]}» — اختر نفس الخزنة المستلمة')
    if row[3]!=dest_currency: raise ValueError(f'الفاتورة بعملة {row[3]} — يجب أن تكون عملة الإضافة {row[3]}')
    paid=sum((Decimal(r[0]) for r in con.execute('SELECT amount FROM halfa_settlements WHERE invoice_id=?',(invoice_id,))),Decimal('0'))
    remaining=Decimal(row[1])-paid
    if amount>remaining: raise ValueError(f'المبلغ أكبر من المتبقي على الفاتورة ({remaining} {row[3]})')
    if amount>Decimal(str(dest_amount)): raise ValueError('المبلغ المخصوم أكبر من المبلغ المستلم في التحويل')
    con.execute('INSERT INTO halfa_settlements(invoice_id,transfer_reference,amount,settle_date,created_by) VALUES(?,?,?,?,?)',
                (invoice_id,transfer_reference,str(amount),settle_date,actor))
    con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
                (actor,'HALFA_SETTLE',f'{row[0]}: {amount} {row[3]} من التحويل {transfer_reference}'))
    return row[0],remaining-amount


def open_factory(root,user,db_path,initial_tab=None,module=None):
    con=sqlite3.connect(db_path)
    con.execute('PRAGMA foreign_keys=ON')
    init(con)
    username=user['username']
    is_manager=user['role']=='manager'
    assigned=con.execute('SELECT role FROM factory_roles WHERE username=?',(username,)).fetchone()
    role='manager' if is_manager else assigned[0] if assigned else 'viewer'
    # Embedded in the existing PMP content host: no independent top-level window.
    window = ctk.CTkFrame(root, fg_color='transparent', corner_radius=0)
    window.pack(fill='both', expand=True)
    # Closing the embedded screen closes its dedicated SQLite connection.
    def _on_destroy(event):
        if event.widget is window:
            try: con.close()
            except sqlite3.Error: pass
    window.bind('<Destroy>', _on_destroy, add='+')
    # V13: the Figma page header (title, subtitle, breadcrumb) is drawn by the application;
    # only the user / permission line stays here.
    ctk.CTkLabel(window, text=ar(f'المستخدم: {username}    |    صلاحية المصنع: {role}    |    بيانات محلية — غير متصلة بالسيرفر'),
                 font=('Segoe UI', 11), text_color='#78869B', anchor='e').pack(fill='x', padx=28, pady=(0, 8))
    # Native tab frames, explicitly packed on selection. CTkTabview inside an
    # embedded page rendered blank on some Windows/Tk scaling configurations.
    tab_names = ['إضافة منتج','الأرصدة','سجل الحركات']
    tab_names.append('الماكينات والأصناف')
    nav = ctk.CTkFrame(window, fg_color='#ECF2FA', corner_radius=12)
    # Navigation is exclusively through the main sidebar.
    # Do not pack the duplicate in-page navigation bar.
    content = ctk.CTkFrame(window, fg_color='transparent', corner_radius=0, border_width=0)
    content.pack(fill='both', expand=True, padx=12, pady=(0, 6))
    panels = {name: ctk.CTkFrame(content, fg_color='#FFFFFF', corner_radius=0) for name in tab_names}
    nav_buttons = {}
    def select_tab(name):
        for other, panel in panels.items():
            panel.pack_forget()
            nav_buttons[other].configure(fg_color='#ECF2FA' if other != name else '#1764D8',
                                         text_color='#18263D' if other != name else '#FFFFFF')
        panels[name].pack(fill='both', expand=True)
    for name in tab_names:
        nav_buttons[name] = ctk.CTkButton(nav, text=ar(name), width=125, height=36,
            corner_radius=9, fg_color='#ECF2FA', text_color='#18263D',
            hover_color='#DCE8FA', command=lambda n=name:select_tab(n))
        # Buttons remain internal to select_tab but are not displayed.
    class TabHost:
        def tab(self, name): return panels[name]
    tabs = TabHost()
    def rows(query,args=()):return con.execute(query,args).fetchall()
    def combos():
        items=rows('SELECT id,name,unit,category FROM factory_items ORDER BY name')
        machines=rows('SELECT id,name FROM factory_machines ORDER BY name')
        return items,machines
    def refresh():
        items,machines=combos()
        refresh_items(); machine_combo.configure(values=['بدون ماكينة']+[f'{i} | {n}' for i,n in machines])
        for table in (balance_tree,history_tree):
            for row in table.get_children():table.delete(row)
        CAT_AR={'raw':'مواد خام','finished':'منتج تام','empty':'براميل فارغة'}
        for i,n,u,c in items:
            if c=='raw': cols=('purchased','raw','production',None)
            elif c=='finished': cols=('finished_pending','finished','loading','can_waste')
            else: cols=(None,'empty',None,None)
            vals=[str(balance(con,i,x)) if x else '-' for x in cols]
            balance_tree.insert('', 'end',values=(n,CAT_AR.get(c,c),u,*vals))
        for r in rows('''SELECT m.id,m.move_date,m.kind,i.name,m.qty,COALESCE(x.name,''),m.batch,m.counterparty,m.actor,m.note FROM factory_moves m JOIN factory_items i ON i.id=m.item_id LEFT JOIN factory_machines x ON x.id=m.machine_id ORDER BY m.id DESC LIMIT 500'''):
            history_tree.insert('','end',values=(r[0],r[1],LABELS.get(r[2],r[2]),r[3],r[4],r[5],r[6],r[7],r[8],r[9]))
    tab=tabs.tab('إضافة منتج')
    import pmp_widgets as W
    MODULE_KINDS = {'raw': ('RAW_PURCHASE','RAW_RECEIPT','ISSUE_PRODUCTION','RETURN_RAW'),
                    'production': ('MACHINE_USE','MACHINE_WASTE','PRODUCTION_OUTPUT','CAN_WASTE'),
                    'finished': ('FINISHED_RECEIPT','HANDOVER_LOADING','DISPATCH_CUSTOMER','DISPATCH_HALFA','EMPTY_IN','EMPTY_OUT')}
    KIND_HINT = {'RAW_PURCHASE':'مشتريات رانيا من الخارج: كمية فقط (بدون تكلفة)، وتبقى «بانتظار الاستلام» حتى يستلمها أمين المخزن.',
                 'PRODUCTION_OUTPUT':'مهندس التصنيع: عدد العلب المنتجة — الماكينة والباتشة مطلوبان، وتنتظر استلام أمين مخزن المنتج التام.',
                 'CAN_WASTE':'مهندس التصنيع: عدد العلب الهالكة — الماكينة والباتشة مطلوبان.',
                 'HANDOVER_LOADING':'تسليم المنتج التام لمسؤول التحميل — تُسجَّل بضاعة خارجة من المصنع، ويُحدَّد المستلم.',
                 'DISPATCH_CUSTOMER':'مسؤول التحميل: خروج بضاعة من عهدته لعميل (اكتب اسم العميل).',
                 'DISPATCH_HALFA':'مسؤول التحميل: ترحيل لمخزن حلفا — يُنشئ فاتورة شراء بالجنيه المصري مستحقة على خزنة مصر.',
                 'RAW_RECEIPT':'استلام خامات إلى مخزن المصنع.',
                 'ISSUE_PRODUCTION':'صرف خامات من المخزن إلى عهدة الإنتاج.',
                 'RETURN_RAW':'إرجاع خامات غير مستهلكة من الإنتاج إلى المخزن.',
                 'MACHINE_USE':'استهلاك خامات على ماكينة — الماكينة ورقم الباتشة مطلوبان.',
                 'MACHINE_WASTE':'تسجيل هالك ماكينة — الماكينة ورقم الباتشة مطلوبان.',
                 'FINISHED_RECEIPT':'استلام منتج تام في مخزن المنتج التام.',
                 'LOAD_FINISHED':'تحميل منتج تام (اذكر العميل أو رقم إذن التحميل في الملاحظات).',
                 'EMPTY_IN':'استلام براميل فارغة.', 'EMPTY_OUT':'صرف براميل فارغة.'}
    wanted_kinds = MODULE_KINDS.get(module) or tuple(k for k in KINDS if k not in LEGACY_KINDS)
    available_kinds=[k for k in KINDS if k in wanted_kinds and k not in LEGACY_KINDS and can_post(con,username,role,k)]
    kind_by_label={LABELS[k]:k for k in available_kinds}
    def category_of(kind):
        return CATEGORY.get(kind,'empty')
    action_bar=ctk.CTkFrame(tab,fg_color='#FFFFFF',corner_radius=0,height=70)   # fixed: the save button never scrolls away
    action_bar.pack(side='bottom',fill='x')
    body=ctk.CTkScrollableFrame(tab,fg_color='transparent',corner_radius=0)
    body.pack(side='top',fill='both',expand=True)
    grid=W.FormGrid(body)
    grid.pack(fill='x',padx=22,pady=(8,0))
    kind_combo=grid.add('نوع الحركة',lambda c:ctk.CTkComboBox(c,values=list(kind_by_label) or ['لا توجد حركات مسموحة'],
        state='readonly',height=42,placeholder='اختر نوع الحركة',command=lambda _v:on_kind()))
    item_combo=grid.add('الصنف',lambda c:ctk.CTkComboBox(c,values=[],state='readonly',height=42,placeholder='اختر الصنف'))
    qty_entry=grid.add('الكمية',lambda c:W.field_entry(c,'0'))
    machine_combo=grid.add('الماكينة (للاستهلاك والهالك)',lambda c:ctk.CTkComboBox(c,values=['بدون ماكينة'],
        state='readonly',height=42,placeholder='بدون ماكينة'))
    batch_entry=grid.add('رقم الباتشة',lambda c:W.field_entry(c,'للاستهلاك والهالك فقط'))
    party_entry=grid.add('الجهة (المورد / المستلم / العميل)',lambda c:W.field_entry(c,'حسب نوع الحركة'))
    product_combo=grid.add('صنف الكتالوج المرحَّل لحلفا',lambda c:ctk.CTkComboBox(c,values=[],state='readonly',height=42,placeholder='لترحيل حلفا فقط'))
    cartons_entry=grid.add('عدد الكراتين المرحَّلة',lambda c:W.field_entry(c,'لترحيل حلفا فقط'))
    price_entry=grid.add('سعر الكرتونة بالجنيه المصري',lambda c:W.field_entry(c,'لترحيل حلفا فقط'))
    note_entry=grid.add('ملاحظات',lambda c:W.field_entry(c,'ملاحظات / رقم إذن التحميل'))
    hint_label=ctk.CTkLabel(body,text='',font=('Segoe UI',12),text_color='#78869B',anchor='e')
    hint_label.pack(fill='x',padx=36,pady=(0,4))
    if available_kinds: kind_combo.set(LABELS[available_kinds[0]])
    def refresh_items():
        kind=kind_by_label.get(kind_combo.get())
        category=category_of(kind) if kind else None
        choices=[f'{i} | {n} ({u})' for i,n,u,c in rows('SELECT id,name,unit,category FROM factory_items ORDER BY name') if category is None or c==category]
        item_combo.configure(values=choices)
        if item_combo.get() not in choices: item_combo.set('')
    def on_kind():
        kind=kind_by_label.get(kind_combo.get())
        machine_kind=kind in MACHINE_KINDS
        halfa=kind=='DISPATCH_HALFA'
        party_kind=kind in NEEDS_RECIPIENT or kind=='RAW_PURCHASE'
        batch_entry.configure(state='normal')
        if not machine_kind:
            machine_combo.set('')
            batch_entry.delete(0,'end')
        machine_combo.configure(state='readonly' if machine_kind else 'disabled')
        batch_entry.configure(state='normal' if machine_kind else 'disabled')
        party_entry.configure(state='normal')
        if not party_kind: party_entry.delete(0,'end')
        party_entry.configure(state='normal' if party_kind else 'disabled')
        for w in (cartons_entry,price_entry):
            w.configure(state='normal')
            if not halfa: w.delete(0,'end')
            w.configure(state='normal' if halfa else 'disabled')
        if not halfa: product_combo.set('')
        product_combo.configure(state='readonly' if halfa else 'disabled',
                                values=[f'{i} | {n}' for i,n in rows('SELECT id,name FROM products WHERE active=1 ORDER BY name')] if halfa else [])
        hint_label.configure(text=ar(KIND_HINT.get(kind,'')))
        refresh_items()
    def save_move():
        try:
            if not available_kinds: raise PermissionError('لا توجد حركات مسموحة لحسابك')
            kind=kind_by_label.get(kind_combo.get())
            if not kind: raise ValueError('اختر نوع الحركة')
            if ' | ' not in item_combo.get(): raise ValueError('اختر الصنف')
            item_id=int(item_combo.get().split(' | ')[0]); m=machine_combo.get()
            mid=int(m.split(' | ')[0]) if ' | ' in m else None
            if kind=='DISPATCH_HALFA':
                if ' | ' not in product_combo.get(): raise ValueError('اختر صنف الكتالوج المرحَّل إلى حلفا')
                with con:
                    _mid,invoice_no,total=dispatch_halfa(con,username,role,item_id,qty_entry.get(),int(product_combo.get().split(' | ')[0]),
                                                         cartons_entry.get(),price_entry.get(),note_entry.get())
                msg=f'تم الترحيل لمخزن حلفا.\nفاتورة الشراء {invoice_no}: {total} {HALFA_CURRENCY} مستحقة على {HALFA_CASHBOX}.'
            else:
                with con:post(con,username,role,kind,item_id,qty_entry.get(),mid,batch_entry.get(),note_entry.get(),party_entry.get())
                msg='تم تسجيل الحركة محلياً'
            for e in (qty_entry,note_entry,cartons_entry,price_entry): 
                if str(e.cget('state'))!='disabled': e.delete(0,'end')
            refresh();messagebox.showinfo('تم',msg,parent=window)
        except Exception as e:messagebox.showerror('تعذر التسجيل',str(e),parent=window)
    W.primary_button(action_bar,ar('حفظ / تنفيذ العملية'),save_move).pack(pady=14)
    def make_table(parent,columns,width=120):
        wrap=ctk.CTkFrame(parent,fg_color='#FFFFFF',corner_radius=12);wrap.pack(fill='both',expand=True,padx=8,pady=8)
        tree=ttk.Treeview(wrap,columns=columns,show='headings')
        for c in columns:tree.heading(c,text=c);tree.column(c,width=width,anchor='center')
        tree.pack(fill='both',expand=True,side='left')
        scroll=ttk.Scrollbar(wrap,orient='vertical',command=tree.yview);scroll.pack(fill='y',side='right');tree.configure(yscrollcommand=scroll.set)
        return tree
    balance_tree=make_table(tabs.tab('الأرصدة'),('الصنف','الفئة','الوحدة','بانتظار الاستلام','رصيد المخزن','عهدة الإنتاج / التحميل','هالك'),130)
    history_tree=make_table(tabs.tab('سجل الحركات'),('رقم','التاريخ','الحركة','الصنف','الكمية','الماكينة','الباتشة','الجهة','الموظف','ملاحظات'),115)
    if is_manager:
        defs=tabs.tab('الماكينات والأصناف')
        ctk.CTkLabel(defs,text=ar('إضافة أصناف وماكينات (للمدير فقط)')).pack(pady=8)
        name=ctk.CTkEntry(defs,placeholder_text=ar('اسم الصنف أو الماكينة'));name.pack(fill='x',padx=25,pady=5)
        unit=ctk.CTkEntry(defs,placeholder_text=ar('الوحدة: كجم / برميل / كرتونة / عبوة'));unit.pack(fill='x',padx=25,pady=5)
        cat=ctk.CTkComboBox(defs,values=['مواد خام','منتج تام','براميل فارغة'],state='readonly');cat.pack(fill='x',padx=25,pady=5)
        def add_item(machine=False):
            if not is_manager:messagebox.showerror('الصلاحيات','تعريف الأصناف للمدير فقط',parent=window);return
            try:
                n=name.get().strip()
                if not n:raise ValueError('الاسم مطلوب')
                with con:
                    if machine:con.execute('INSERT INTO factory_machines(name) VALUES(?)',(n,))
                    else:
                        if not unit.get().strip():raise ValueError('الوحدة مطلوبة')
                        con.execute('INSERT INTO factory_items(name,unit,category) VALUES(?,?,?)',(n,unit.get().strip(),{'مواد خام':'raw','منتج تام':'finished','براميل فارغة':'empty'}[cat.get()]))
                name.delete(0,'end');refresh();messagebox.showinfo('تم','تمت الإضافة',parent=window)
            except Exception as e:messagebox.showerror('خطأ',str(e),parent=window)
        ctk.CTkButton(defs,text=ar('إضافة صنف'),command=add_item).pack(pady=7)
        ctk.CTkButton(defs,text=ar('إضافة ماكينة'),command=lambda:add_item(True)).pack(pady=7)
    ctk.CTkButton(window,text=ar('تحديث البيانات'),command=refresh, height=36, corner_radius=10, fg_color='#1764D8').pack(pady=(0,10))
    refresh()
    on_kind()
    select_tab(initial_tab if initial_tab in panels else 'إضافة منتج')
