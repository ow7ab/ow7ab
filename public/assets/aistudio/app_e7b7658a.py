import sqlite3
import hashlib
import secrets
import hmac
import getpass
import sys
import csv
from datetime import datetime
from tkinter import filedialog
# Windows high-DPI awareness must be enabled before the first Tk window.
# Without this, Windows may bitmap-scale the entire app, making text and icons blurry.
if sys.platform == 'win32':
    try:
        import ctypes
        # Per-monitor DPI awareness v2: crisp drawing when moved between monitors.
        ctypes.windll.user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))
    except (AttributeError, OSError, ValueError):
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(2)
        except (AttributeError, OSError, ValueError):
            pass

import tkinter as tk
from tkinter import ttk, messagebox
import customtkinter as ctk
import pmp_widgets
pmp_widgets.install()   # V14: Figma-style drop-downs for every combo box / option menu
from pathlib import Path
from datetime import date
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
import json
import os
import subprocess
import tempfile
import shutil
import time
import threading
import queue
import traceback
import logging
from contextlib import closing
from tkinter import font as tkfont

APP_VERSION = 'V15 FIGMA SCREENS TEST'

# PMP V7: unified administrative windows and scrollable dashboard with rounded controls, preserving the V6 accounting/database functions.
ctk.set_appearance_mode('light')
ctk.set_default_color_theme('blue')

# V9 Material 3-inspired PMP color tokens (blue + gold).
M3 = {'primary':'#1764D8','primary_hover':'#1250B0','gold':'#E9B75B',
      'surface':'#FFFFFF','surface_dim':'#F5F8FC','background':'#F4F7FC',
      'outline':'#E3EAF4','ink':'#18263D','muted':'#78869B',
      'nav':'#0C1D38','nav_item':'#112744','nav_active':'#244A78','nav_card':'#1B385A',
      'green':'#159D76','purple':'#805AD5','gold_dark':'#C79535',
      'table_head':'#ECF2FA','table_alt':'#F9FBFE','chart_soft':'#B3CEF3'}
# V11.8: palette and layout follow the Figma file "PMP Cash System - Professional Desktop Redesign".



# V9.0: shared Material-3-inspired defaults for ALL screens and pop-up windows.
# These wrappers only supply missing visual parameters and do not alter commands/data.
_BaseFrame = ctk.CTkFrame
_BaseTop = ctk.CTkToplevel
_BaseTab = ctk.CTkTabview
_BaseButton = ctk.CTkButton
_BaseEntry = ctk.CTkEntry
_BaseCombo = ctk.CTkComboBox

class MaterialFrame(_BaseFrame):
    def __init__(self, *args, **kwargs):
        kwargs.setdefault('fg_color', M3['surface_dim'])
        kwargs.setdefault('corner_radius', 14)
        kwargs.setdefault('border_color', M3['outline'])
        super().__init__(*args, **kwargs)

class MaterialTop(_BaseTop):
    def __init__(self, *args, **kwargs):
        kwargs.setdefault('fg_color', M3['background'])
        super().__init__(*args, **kwargs)

class MaterialTab(_BaseTab):
    def __init__(self, *args, **kwargs):
        kwargs.setdefault('fg_color', M3['surface'])
        kwargs.setdefault('segmented_button_fg_color', M3['surface_dim'])
        kwargs.setdefault('segmented_button_selected_color', M3['primary'])
        kwargs.setdefault('segmented_button_selected_hover_color', M3['primary_hover'])
        kwargs.setdefault('segmented_button_unselected_color', M3['surface_dim'])
        kwargs.setdefault('text_color', M3['ink'])
        super().__init__(*args, **kwargs)

class MaterialButton(_BaseButton):
    def __init__(self, *args, **kwargs):
        kwargs.setdefault('corner_radius', 12)
        kwargs.setdefault('fg_color', M3['primary'])
        kwargs.setdefault('hover_color', M3['primary_hover'])
        super().__init__(*args, **kwargs)

class MaterialEntry(_BaseEntry):
    def __init__(self, *args, **kwargs):
        kwargs.setdefault('corner_radius', 10)
        kwargs.setdefault('border_color', M3['outline'])
        super().__init__(*args, **kwargs)

class MaterialCombo(_BaseCombo):
    def __init__(self, *args, **kwargs):
        kwargs.setdefault('corner_radius', 10)
        kwargs.setdefault('border_color', M3['outline'])
        kwargs.setdefault('button_color', M3['primary'])
        kwargs.setdefault('button_hover_color', M3['primary_hover'])
        super().__init__(*args, **kwargs)

ctk.CTkFrame = MaterialFrame
ctk.CTkToplevel = MaterialTop
ctk.CTkTabview = MaterialTab
ctk.CTkButton = MaterialButton
ctk.CTkEntry = MaterialEntry
ctk.CTkComboBox = MaterialCombo

# Restore CustomTkinter's native mouse-wheel dispatch. The previous custom
# _mouse_wheel_all override blocked wheel events when hovering child widgets.
# Keep the stock CTkScrollableFrame implementation so normal wheel and
# trackpad scrolling work across nested page contents.
SmoothScrollableFrame = ctk.CTkScrollableFrame

def apply_table_theme(root):
    """Apply a shared blue-and-gold table style to ttk tables on every page."""
    style = ttk.Style(root)
    try:
        style.theme_use('clam')
    except tk.TclError:
        pass
    style.configure('Treeview', background='#FFFFFF', fieldbackground='#FFFFFF',
                    foreground=M3['ink'], rowheight=30, borderwidth=0,
                    font=('Segoe UI', 10))
    style.configure('Treeview.Heading', background=M3['table_head'], foreground=M3['ink'],
                    relief='flat', padding=(8, 9), font=('Segoe UI', 10, 'bold'))
    style.map('Treeview', background=[('selected', '#DCE8FA')],
              foreground=[('selected', M3['ink'])])
    style.map('Treeview.Heading', background=[('active', '#D5E5F6')])


def format_money(value):
    """Display a monetary amount without trailing zero decimals; never change stored data."""
    number = Decimal(str(value))
    if not number.is_finite():
        return str(value)
    # Preserve meaningful precision, including fractions smaller than 0.01.
    places = max(2, -number.as_tuple().exponent)
    rendered = f'{number:,.{places}f}'
    return rendered.rstrip('0').rstrip('.') if '.' in rendered else rendered


class PMPDialog:
    """Modal, centered, PMP-branded replacement for informational and confirmation dialogs."""
    @staticmethod
    def _show(kind, title, message, parent=None):
        host = parent if parent is not None and parent.winfo_exists() else tk._default_root
        if host is None:
            raise RuntimeError('No active window for dialog')
        dialog = ctk.CTkToplevel(host)
        dialog.withdraw()
        dialog.title(f'PMP Paints | {title}')
        dialog.resizable(False, False)
        dialog.configure(fg_color='#EDF2F8')
        width, height = 480, 295 if kind == 'question' else 265
        dialog.geometry(f'{width}x{height}')
        dialog.transient(host.winfo_toplevel())
        result = {'value': False}
        outer = ctk.CTkFrame(dialog, fg_color='white', corner_radius=18,
                             border_width=1, border_color='#DCE5F0')
        outer.pack(fill='both', expand=True, padx=14, pady=14)
        icons = {'info': ('✓', '#13845C'), 'error': ('!', '#C73D49'),
                 'warning': ('!', '#C48A1D'), 'question': ('?', '#135BC8')}
        symbol, accent = icons[kind]
        ctk.CTkLabel(outer, text=symbol, width=48, height=48,
                     fg_color=accent, corner_radius=24, text_color='white',
                     font=('Segoe UI', 23, 'bold')).pack(pady=(16, 5))
        ctk.CTkLabel(outer, text=title, font=('Segoe UI', 16, 'bold'),
                     text_color=M3['ink']).pack(pady=(0, 6))
        ctk.CTkLabel(outer, text=str(message), font=('Segoe UI', 12),
                     text_color='#425773', justify='center', wraplength=410).pack(
                         fill='both', expand=True, padx=18, pady=(0, 7))
        buttons = ctk.CTkFrame(outer, fg_color='transparent')
        buttons.pack(pady=(0, 15))
        def finish(value=False):
            result['value'] = value
            dialog.destroy()
        if kind == 'question':
            RoundedButton(buttons, text='تأكيد', width=132,
                          command=lambda: finish(True)).pack(side='right', padx=6)
            RoundedButton(buttons, text='إلغاء', width=132, fg_color='#E5EDF7',
                          hover_color='#D5E1F0', text_color=M3['ink'],
                          command=lambda: finish(False)).pack(side='right', padx=6)
        else:
            RoundedButton(buttons, text='موافق', width=175,
                          command=lambda: finish(False)).pack()
        dialog.protocol('WM_DELETE_WINDOW', lambda: finish(False))
        dialog.bind('<Escape>', lambda _e: finish(False))
        dialog.update_idletasks()
        host_top = host.winfo_toplevel()
        x = host_top.winfo_rootx() + max(0, (host_top.winfo_width() - width) // 2)
        y = host_top.winfo_rooty() + max(0, (host_top.winfo_height() - height) // 2)
        dialog.geometry(f'{width}x{height}+{x}+{y}')
        dialog.deiconify()
        dialog.grab_set()
        dialog.focus_force()
        host.wait_window(dialog)
        return result['value']

    @classmethod
    def showinfo(cls, title, message, parent=None, **_kwargs):
        return cls._show('info', title, message, parent)

    @classmethod
    def showerror(cls, title, message, parent=None, **_kwargs):
        return cls._show('error', title, message, parent)

    @classmethod
    def showwarning(cls, title, message, parent=None, **_kwargs):
        return cls._show('warning', title, message, parent)

    @classmethod
    def askyesno(cls, title, message, parent=None, **_kwargs):
        return cls._show('question', title, message, parent)


messagebox = PMPDialog

class RoundedEntry(ctk.CTkEntry):
    def __init__(self, master=None, width=23, **kwargs):
        super().__init__(master, width=max(125, int(width)*9), height=36,
                         corner_radius=11, border_width=1, border_color=M3['outline'],
                         fg_color='#FFFFFF', text_color=M3['ink'],
                         font=('Segoe UI', 12), **kwargs)
        self.bind('<FocusIn>', lambda _e: self.configure(border_color=M3['primary']), add='+')
        self.bind('<FocusOut>', lambda _e: self.configure(border_color=M3['outline']), add='+')

class RoundedCombo(ctk.CTkComboBox):
    def __init__(self, master=None, width=20, values=(), state='normal', **kwargs):
        super().__init__(master, width=max(125, int(width)*9), height=36,
                         corner_radius=11, border_width=1, border_color=M3['outline'],
                         fg_color='#FFFFFF', text_color=M3['ink'],
                         button_color=M3['primary'], button_hover_color=M3['primary_hover'],
                         dropdown_font=('Segoe UI', 11), font=('Segoe UI', 12),
                         values=list(values), state=state, **kwargs)
        self.bind('<FocusIn>', lambda _e: self.configure(border_color=M3['primary'], border_width=2), add='+')
        self.bind('<FocusOut>', lambda _e: self.configure(border_color=M3['outline'], border_width=1), add='+')

class RoundedButton(ctk.CTkButton):
    def __init__(self, master=None, text='', command=None, **kwargs):
        opts=dict(height=42, corner_radius=16, fg_color=M3['primary'],
                  hover_color=M3['primary_hover'], text_color='#FFFFFF', font=('Segoe UI', 11, 'bold'))
        opts.update(kwargs)
        super().__init__(master, text=text, command=command, **opts)

# Only entry, combo and button controls are replaced. Tables and labels stay ttk.

APP_DIR = Path(sys.executable).resolve().parent if getattr(sys, 'frozen', False) else Path(__file__).resolve().parent
DB = APP_DIR / 'pmp_cash.db'

def connection():
    con = sqlite3.connect(DB)
    con.execute('PRAGMA foreign_keys=ON')
    return con

def validate_backup_database(path):
    """Check an SQLite backup without retaining a Windows file handle."""
    candidate = Path(path).resolve()
    if not candidate.is_file() or candidate == DB.resolve():
        raise ValueError('اختر ملف نسخة احتياطية مختلفاً عن قاعدة البيانات الحالية')
    uri = candidate.as_uri() + '?mode=ro'
    # sqlite3's `with` commits/rolls back but DOES NOT close its connection.
    # An explicit close is essential before os.replace/unlink on Windows.
    with closing(sqlite3.connect(uri, uri=True)) as con:
        integrity = con.execute('PRAGMA integrity_check').fetchone()
        if not integrity or integrity[0] != 'ok':
            raise ValueError('ملف النسخة الاحتياطية غير سليم')
        if con.execute('PRAGMA foreign_key_check').fetchone():
            raise ValueError('النسخة تحتوي على علاقات بيانات غير سليمة')
        tables = {row[0] for row in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        needed = {'users', 'movements', 'audit_log', 'change_requests', 'movement_changes', 'daily_closings'}
        missing = needed - tables
        if missing:
            raise ValueError('النسخة غير متوافقة مع V7.5.1؛ جداول ناقصة: ' + ', '.join(sorted(missing)))
        users = {row[1] for row in con.execute('PRAGMA table_info(users)')}
        if not {'username', 'password_hash', 'role', 'active'} <= users:
            raise ValueError('النسخة لا تحتوي على بيانات المستخدمين المطلوبة')
    return candidate


def restore_backup_safely(source, username):
    """Stage and validate; preserve current DB before replacement."""
    source = validate_backup_database(source)
    DB.parent.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime('%Y%m%d_%H%M%S_%f')
    safety = DB.with_name('PMP_before_restore_' + stamp + '.db')
    staged = DB.with_name('.PMP_restore_' + stamp + '.db')
    replaced = False
    try:
        with closing(sqlite3.connect(source.as_uri() + '?mode=ro', uri=True)) as incoming:
            with closing(sqlite3.connect(staged)) as output:
                incoming.backup(output)
        validate_backup_database(staged)
        if DB.exists():
            with closing(connection()) as current:
                with closing(sqlite3.connect(safety)) as saved:
                    current.backup(saved)
            with closing(sqlite3.connect(safety)) as verify_safety:
                if verify_safety.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
                    raise ValueError('تعذر التحقق من نسخة الأمان الحالية')
        with closing(sqlite3.connect(staged)) as check:
            check.execute('PRAGMA journal_mode=DELETE')
            if check.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
                raise ValueError('فشل التحقق النهائي من الملف المسترد')
        os.replace(staged, DB)
        replaced = True
        setup()  # adds V7.9.2 customer/invoice tables if the backup is older
        with closing(connection()) as con:
            with con:
                con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
                            (username, 'RESTORE_BACKUP', 'استرداد نسخة: ' + source.name + ' / نسخة أمان: ' + safety.name))
        return safety
    finally:
        if staged.exists():
            try:
                staged.unlink()
            except OSError:
                # Never mask the original error; retain staged file for diagnosis.
                pass


def password_hash(password, salt=None):
    salt = salt or secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac('sha256', password.encode('utf-8'), bytes.fromhex(salt), 250000)
    return salt + ':' + digest.hex()

def verify_password(password, saved):
    try:
        salt, expected = saved.split(':', 1)
        actual = password_hash(password, salt).split(':', 1)[1]
        return hmac.compare_digest(actual, expected)
    except (ValueError, TypeError):
        return False

def setup():
    with connection() as con:
        con.execute('''CREATE TABLE IF NOT EXISTS movements (id INTEGER PRIMARY KEY AUTOINCREMENT, tx_date TEXT NOT NULL, cashbox TEXT NOT NULL, currency TEXT NOT NULL, direction TEXT NOT NULL CHECK(direction IN ('وارد','منصرف')), amount REAL NOT NULL CHECK(amount>0), description TEXT NOT NULL, created_at TEXT DEFAULT CURRENT_TIMESTAMP)''')
        con.execute('''CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL, role TEXT NOT NULL CHECK(role IN ('manager','accountant','deputy')), password_hash TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1)''')
        # Additive V9.1 migration; existing credentials and financial records remain intact.
        profile_cols = {r[1] for r in con.execute('PRAGMA table_info(users)')}
        for col, typ in [('phone','TEXT'),('email','TEXT'),('photo_path','TEXT'),
                         ('address','TEXT'),('birth_date','TEXT'),('job_title','TEXT'),
                         ('must_change_password','INTEGER NOT NULL DEFAULT 0')]:
            if col not in profile_cols:
                con.execute(f'ALTER TABLE users ADD COLUMN {col} {typ}')
        con.execute('''CREATE TABLE IF NOT EXISTS audit_log (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL, action TEXT NOT NULL, detail TEXT NOT NULL, created_at TEXT DEFAULT CURRENT_TIMESTAMP)''')
        cols={r[1] for r in con.execute('PRAGMA table_info(movements)')}
        if 'created_by' not in cols:
            con.execute('ALTER TABLE movements ADD COLUMN created_by TEXT')
        if 'reference' not in cols:
            con.execute('ALTER TABLE movements ADD COLUMN reference TEXT')
        con.execute('CREATE UNIQUE INDEX IF NOT EXISTS idx_movements_reference ON movements(reference) WHERE reference IS NOT NULL')
        con.execute('''CREATE TABLE IF NOT EXISTS transfers (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            reference TEXT NOT NULL UNIQUE,
            tx_date TEXT NOT NULL,
            source_cashbox TEXT NOT NULL,
            destination_cashbox TEXT NOT NULL,
            source_currency TEXT NOT NULL,
            destination_currency TEXT NOT NULL,
            source_amount TEXT NOT NULL,
            rate TEXT NOT NULL,
            destination_amount TEXT NOT NULL,
            description TEXT NOT NULL,
            created_by TEXT NOT NULL,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        )''')

        con.execute("""CREATE TABLE IF NOT EXISTS change_requests (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            movement_id INTEGER NOT NULL REFERENCES movements(id),
            request_type TEXT NOT NULL CHECK(request_type IN ('correct','cancel')),
            proposed_json TEXT,
            reason TEXT NOT NULL,
            requested_by TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','approved','rejected')),
            decided_by TEXT,
            decision_note TEXT,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            decided_at TEXT,
            original_json TEXT NOT NULL
        )""")
        con.execute("""CREATE TABLE IF NOT EXISTS movement_changes (
            movement_id INTEGER PRIMARY KEY REFERENCES movements(id),
            request_id INTEGER NOT NULL REFERENCES change_requests(id),
            action TEXT NOT NULL,
            replacement_movement_id INTEGER REFERENCES movements(id),
            decided_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )""")
        # V7.2 compatibility: Older databases have a UNIQUE constraint on request_id.
        # A transfer cancellation needs TWO movement_changes rows for one request.
        # Rebuild only this tracking table, preserving existing rows and IDs.
        table_sql = con.execute("SELECT sql FROM sqlite_master WHERE type='table' AND name='movement_changes'").fetchone()[0]
        if 'request_id INTEGER NOT NULL UNIQUE' in table_sql:
            con.execute("""CREATE TABLE movement_changes_v72 (
                movement_id INTEGER PRIMARY KEY REFERENCES movements(id),
                request_id INTEGER NOT NULL REFERENCES change_requests(id),
                action TEXT NOT NULL,
                replacement_movement_id INTEGER REFERENCES movements(id),
                decided_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )""")
            con.execute("""INSERT INTO movement_changes_v72
                (movement_id,request_id,action,replacement_movement_id,decided_at)
                SELECT movement_id,request_id,action,replacement_movement_id,decided_at
                FROM movement_changes""")
            con.execute('DROP TABLE movement_changes')
            con.execute('ALTER TABLE movement_changes_v72 RENAME TO movement_changes')
        con.execute('CREATE INDEX IF NOT EXISTS idx_movement_changes_request ON movement_changes(request_id)')
        con.execute('CREATE INDEX IF NOT EXISTS idx_change_requests_status ON change_requests(status)')
        con.execute("""CREATE TABLE IF NOT EXISTS daily_closings (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            close_date TEXT NOT NULL, cashbox TEXT NOT NULL, currency TEXT NOT NULL,
            book_balance TEXT NOT NULL, counted_balance TEXT NOT NULL, difference TEXT NOT NULL,
            notes TEXT NOT NULL DEFAULT '', prepared_by TEXT NOT NULL,
            approved_by TEXT NOT NULL, closed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            reopened_at TEXT, reopened_by TEXT, reopen_reason TEXT,
            status TEXT NOT NULL DEFAULT 'closed' CHECK(status IN ('closed','reopened'))
        )""")
        con.execute("""CREATE UNIQUE INDEX IF NOT EXISTS idx_active_daily_closing
            ON daily_closings(close_date,cashbox,currency) WHERE status='closed'""")
        con.execute('CREATE INDEX IF NOT EXISTS idx_daily_closing_lookup ON daily_closings(cashbox,currency,close_date)')

        # V7.9.2: customers and invoices. Money is stored as TEXT decimals (exact), like transfers.
        con.execute("""CREATE TABLE IF NOT EXISTS customers (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL COLLATE NOCASE,
            phone TEXT NOT NULL DEFAULT '',
            address TEXT NOT NULL DEFAULT '',
            notes TEXT NOT NULL DEFAULT '',
            active INTEGER NOT NULL DEFAULT 1,
            created_by TEXT NOT NULL DEFAULT '',
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )""")
        con.execute('CREATE UNIQUE INDEX IF NOT EXISTS idx_customers_name_phone ON customers(name, phone)')
        con.execute("""CREATE TABLE IF NOT EXISTS invoices (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            invoice_no TEXT NOT NULL UNIQUE,
            customer_id INTEGER NOT NULL REFERENCES customers(id),
            invoice_date TEXT NOT NULL,
            currency TEXT NOT NULL,
            subtotal TEXT NOT NULL,
            discount TEXT NOT NULL DEFAULT '0',
            total TEXT NOT NULL,
            notes TEXT NOT NULL DEFAULT '',
            status TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('active','cancelled')),
            cancel_reason TEXT,
            cancelled_by TEXT,
            cancelled_at TEXT,
            created_by TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )""")
        con.execute("""CREATE TABLE IF NOT EXISTS invoice_items (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            invoice_id INTEGER NOT NULL REFERENCES invoices(id),
            description TEXT NOT NULL,
            quantity TEXT NOT NULL,
            unit_price TEXT NOT NULL,
            line_total TEXT NOT NULL
        )""")
        con.execute("""CREATE TABLE IF NOT EXISTS invoice_payments (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            invoice_id INTEGER NOT NULL REFERENCES invoices(id),
            pay_date TEXT NOT NULL,
            cashbox TEXT NOT NULL,
            amount TEXT NOT NULL,
            note TEXT NOT NULL DEFAULT '',
            movement_id INTEGER NOT NULL REFERENCES movements(id),
            status TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('active','reversed')),
            reversal_movement_id INTEGER REFERENCES movements(id),
            reversal_reason TEXT,
            reversed_by TEXT,
            reversed_at TEXT,
            created_by TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )""")
        # V8: independent merchandise warehouses, catalog and immutable stock ledger.
        con.execute("""CREATE TABLE IF NOT EXISTS warehouses (
            id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE COLLATE NOCASE,
            active INTEGER NOT NULL DEFAULT 1, created_at TEXT DEFAULT CURRENT_TIMESTAMP)""")
        con.execute("""CREATE TABLE IF NOT EXISTS products (
            id INTEGER PRIMARY KEY, sku TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
            category TEXT NOT NULL DEFAULT '', unit TEXT NOT NULL DEFAULT 'قطعة',
            purchase_price TEXT NOT NULL DEFAULT '0', sale_price TEXT NOT NULL DEFAULT '0',
            minimum_stock TEXT NOT NULL DEFAULT '0', active INTEGER NOT NULL DEFAULT 1)""")
        con.execute("""CREATE TABLE IF NOT EXISTS stock_moves (
            id INTEGER PRIMARY KEY, warehouse_id INTEGER NOT NULL REFERENCES warehouses(id),
            product_id INTEGER NOT NULL REFERENCES products(id), quantity TEXT NOT NULL,
            kind TEXT NOT NULL CHECK(kind IN ('IN','OUT','SALE','RETURN')),
            invoice_id INTEGER REFERENCES invoices(id), note TEXT NOT NULL DEFAULT '',
            created_by TEXT NOT NULL, created_at TEXT DEFAULT CURRENT_TIMESTAMP)""")
        if 'package_weight' not in {r[1] for r in con.execute('PRAGMA table_info(products)')}:
            con.execute("ALTER TABLE products ADD COLUMN package_weight TEXT NOT NULL DEFAULT ''")
        if 'paint_color' not in {r[1] for r in con.execute('PRAGMA table_info(products)')}:
            con.execute("ALTER TABLE products ADD COLUMN paint_color TEXT NOT NULL DEFAULT ''")
        con.execute('CREATE INDEX IF NOT EXISTS idx_stock_balance ON stock_moves(warehouse_id,product_id)')
        existing_columns={r[1] for r in con.execute('PRAGMA table_info(invoice_items)')}
        if 'product_id' not in existing_columns:
            con.execute('ALTER TABLE invoice_items ADD COLUMN product_id INTEGER REFERENCES products(id)')
        if 'warehouse_id' not in existing_columns:
            con.execute('ALTER TABLE invoice_items ADD COLUMN warehouse_id INTEGER REFERENCES warehouses(id)')
        con.execute('CREATE INDEX IF NOT EXISTS idx_invoices_customer ON invoices(customer_id)')
        con.execute('CREATE INDEX IF NOT EXISTS idx_invoice_items_invoice ON invoice_items(invoice_id)')
        con.execute('CREATE INDEX IF NOT EXISTS idx_invoice_payments_invoice ON invoice_payments(invoice_id)')
        con.execute("""CREATE TABLE IF NOT EXISTS cashboxes (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE COLLATE NOCASE,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )""")
        # Seed legacy names only for a fresh database. Never resurrect renamed boxes.
        if not con.execute('SELECT 1 FROM cashboxes LIMIT 1').fetchone():
            for original in ('خزنة السودان','خزنة مصر','مخزن عطبرة','مخزن حلفا'):
                con.execute('INSERT OR IGNORE INTO cashboxes(name) VALUES(?)',(original,))
        # Repair V7.9's unintended reseeding of a renamed legacy box. Only remove
        # an orphan if audit history proves a rename and no financial records use it.
        import pmp_factory
        pmp_factory.init_halfa(con)
        for (detail,) in con.execute("SELECT detail FROM audit_log WHERE action='RENAME_CASHBOX'"):
            if ' -> ' not in detail:
                continue
            old, _new = detail.split(' -> ', 1)
            if old not in ('خزنة السودان','خزنة مصر','مخزن عطبرة','مخزن حلفا'):
                continue
            used = any(con.execute(f'SELECT 1 FROM {table} WHERE {col}=? LIMIT 1', (old,)).fetchone()
                       for table,col in [('movements','cashbox'),('transfers','source_cashbox'),
                                         ('transfers','destination_cashbox'),('daily_closings','cashbox'),('halfa_invoices','cashbox')])
            if not used:
                con.execute('DELETE FROM cashboxes WHERE name=?', (old,))
        # Preserve boxes from legacy data or restored backups.
        for table, col in [('movements','cashbox'),('transfers','source_cashbox'),
                           ('transfers','destination_cashbox'),('daily_closings','cashbox'),('halfa_invoices','cashbox')]:
            for (name,) in con.execute(f'SELECT DISTINCT {col} FROM {table} WHERE {col} IS NOT NULL'):
                con.execute('INSERT OR IGNORE INTO cashboxes(name) VALUES(?)',(name,))
        global CASHBOXES
        CASHBOXES = tuple(r[0] for r in con.execute('SELECT name FROM cashboxes ORDER BY id'))


def log_action(username, action, detail):
    with connection() as con:
        con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)', (username,action,detail))

def create_first_manager():
    with connection() as con:
        count=con.execute('SELECT COUNT(*) FROM users').fetchone()[0]
    if count:
        print('يوجد مستخدمون بالفعل؛ لن يتم إنشاء حساب مدير جديد.')
        return
    print('إنشاء حساب المدير الأول (لا تظهر كلمة المرور أثناء الكتابة).')
    username=input('اسم المستخدم للمدير [manager]: ').strip() or 'manager'
    display=input('اسم المدير الظاهر [المدير]: ').strip() or 'المدير'
    pw=getpass.getpass('كلمة المرور (12 حرفاً على الأقل): ')
    confirm=getpass.getpass('تأكيد كلمة المرور: ')
    if len(pw)<12 or pw!=confirm:
        print('فشل: كلمة المرور قصيرة أو غير متطابقة. لم يتم إنشاء المستخدم.')
        return
    with connection() as con:
        con.execute('INSERT INTO users(username,display_name,role,password_hash) VALUES(?,?,?,?)', (username,display,'manager',password_hash(pw)))
    print('تم إنشاء المدير. شغّل البرنامج باستخدام python app.py')


CASHBOXES=('خزنة السودان','خزنة مصر','مخزن عطبرة','مخزن حلفا')
CURRENCIES=('SDG','EGP','USD')

def manage_cashbox(operation, name, username, old_name=None):
    name = (name or '').strip()
    if operation != 'delete' and not name or len(name)>90 or any(ch in name for ch in '\n\r\t'):
        raise ValueError('أدخل اسم خزنة صحيحاً (حتى 90 حرفاً).')
    with connection() as con:
        con.execute('BEGIN IMMEDIATE')
        if operation != 'delete' and con.execute('SELECT 1 FROM cashboxes WHERE name=? COLLATE NOCASE',(name,)).fetchone():
            raise ValueError('اسم الخزنة مستخدم بالفعل.')
        if operation == 'add':
            con.execute('INSERT INTO cashboxes(name) VALUES(?)',(name,))
            action, detail = 'ADD_CASHBOX',name
        elif operation == 'rename':
            if not old_name or not con.execute('SELECT 1 FROM cashboxes WHERE name=?',(old_name,)).fetchone():
                raise ValueError('الخزنة القديمة غير موجودة.')
            con.execute('UPDATE cashboxes SET name=? WHERE name=?',(name,old_name))
            for table, col in [('movements','cashbox'),('transfers','source_cashbox'),
                               ('transfers','destination_cashbox'),('daily_closings','cashbox'),('halfa_invoices','cashbox')]:
                con.execute(f'UPDATE {table} SET {col}=? WHERE {col}=?',(name,old_name))
            # Pending correction requests must point to the renamed box to remain approvable.
            for rid, proposal in con.execute("SELECT id,proposed_json FROM change_requests WHERE status='pending' AND proposed_json IS NOT NULL"):
                try:
                    data=json.loads(proposal)
                    if isinstance(data,dict) and data.get('cashbox')==old_name:
                        data['cashbox']=name
                        con.execute('UPDATE change_requests SET proposed_json=? WHERE id=?',
                                    (json.dumps(data,ensure_ascii=False),rid))
                except (ValueError,TypeError):
                    pass
            action, detail = 'RENAME_CASHBOX',f'{old_name} -> {name}'
        elif operation == 'delete':
            if not old_name or not con.execute('SELECT 1 FROM cashboxes WHERE name=?',(old_name,)).fetchone():
                raise ValueError('الخزنة غير موجودة.')
            for table,col in [('movements','cashbox'),('transfers','source_cashbox'),
                              ('transfers','destination_cashbox'),('daily_closings','cashbox'),('halfa_invoices','cashbox')]:
                if con.execute(f'SELECT 1 FROM {table} WHERE {col}=? LIMIT 1',(old_name,)).fetchone():
                    raise ValueError('لا يمكن حذف خزنة مرتبطة بحركات أو تحويلات أو إقفالات. حفاظاً على السجل المالي.')
            for (proposal,) in con.execute("SELECT proposed_json FROM change_requests WHERE status='pending' AND proposed_json IS NOT NULL"):
                try:
                    data=json.loads(proposal)
                    if isinstance(data,dict) and data.get('cashbox')==old_name:
                        raise ValueError('لا يمكن حذف خزنة مرتبطة بطلب تصحيح معلق.')
                except (TypeError, json.JSONDecodeError):
                    continue
            con.execute('DELETE FROM cashboxes WHERE name=?',(old_name,))
            action,detail='DELETE_CASHBOX',old_name
        else:
            raise ValueError('عملية غير معروفة')
        con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',(username,action,detail))
        global CASHBOXES
        CASHBOXES=tuple(r[0] for r in con.execute('SELECT name FROM cashboxes ORDER BY id'))



def parse_positive(value, label, maximum='999999999999.99'):
    try:
        amount=Decimal(str(value))
    except (InvalidOperation, ValueError):
        raise ValueError(f'{label}: أدخل رقماً صحيحاً')
    if not amount.is_finite() or amount<=0 or amount>Decimal(maximum):
        raise ValueError(f'{label}: القيمة خارج الحدود المسموحة')
    return amount

def require_open_day(con, tx_date, cashbox, currency):
    """Call inside the same BEGIN IMMEDIATE transaction as the financial posting."""
    locked=con.execute("""SELECT id FROM daily_closings
        WHERE close_date=? AND cashbox=? AND currency=? AND status='closed' LIMIT 1""",
        (tx_date,cashbox,currency)).fetchone()
    if locked:
        raise ValueError(f'اليوم {tx_date} مقفل للخزنة {cashbox} / {currency}. يلزم إعادة فتحه بواسطة المدير أو نائب المدير.')


def close_day(close_date, cashbox, currency, counted, notes, username, role):
    if role not in ('manager','deputy'):
        raise PermissionError('اعتماد الإقفال للمدير ونائب المدير فقط')
    date.fromisoformat(close_date)
    if close_date > date.today().isoformat():
        raise ValueError('لا يمكن إقفال تاريخ مستقبلي')
    if cashbox not in CASHBOXES or currency not in CURRENCIES:
        raise ValueError('الخزنة أو العملة غير صحيحة')
    counted_value=Decimal(str(counted))
    if not counted_value.is_finite() or counted_value < 0 or counted_value.as_tuple().exponent < -2:
        raise ValueError('الرصيد الفعلي يجب أن يكون غير سالب وبحد أقصى منزلتين عشريتين')
    if counted_value > Decimal('999999999999.99'):
        raise ValueError('الرصيد الفعلي أكبر من الحد المسموح')
    with connection() as con:
        con.execute('BEGIN IMMEDIATE')
        require_open_day(con,close_date,cashbox,currency)
        entries=con.execute("""SELECT direction,amount FROM movements
            WHERE tx_date<=? AND cashbox=? AND currency=?""",(close_date,cashbox,currency)).fetchall()
        cents=sum((1 if direction=='وارد' else -1)*_cents(amount) for direction,amount in entries)
        book=Decimal(cents)/100
        diff=counted_value-book
        con.execute("""INSERT INTO daily_closings
            (close_date,cashbox,currency,book_balance,counted_balance,difference,notes,prepared_by,approved_by)
            VALUES(?,?,?,?,?,?,?,?,?)""",(close_date,cashbox,currency,str(book),str(counted_value),str(diff),notes.strip(),username,username))
        con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
            (username,'CLOSE_DAY',f'{close_date} / {cashbox} / {currency} / دفتري {book} / فعلي {counted_value} / فرق {diff}'))
    return book,diff


def reopen_day(closing_id, reason, username, role):
    if role not in ('manager','deputy'):
        raise PermissionError('إعادة فتح الإقفال للمدير ونائب المدير فقط')
    if not reason.strip():
        raise ValueError('سبب إعادة الفتح إلزامي')
    with connection() as con:
        con.execute('BEGIN IMMEDIATE')
        row=con.execute("SELECT close_date,cashbox,currency,status FROM daily_closings WHERE id=?",(closing_id,)).fetchone()
        if not row or row[3]!='closed':
            raise ValueError('الإقفال غير موجود أو تمت إعادة فتحه')
        con.execute("""UPDATE daily_closings SET status='reopened',reopened_at=CURRENT_TIMESTAMP,
            reopened_by=?,reopen_reason=? WHERE id=? AND status='closed'""",(username,reason.strip(),closing_id))
        con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
            (username,'REOPEN_DAY',f'إقفال {closing_id} / {row[0]} / {row[1]} / {row[2]} / السبب: {reason.strip()}'))


def post_transfer(data, username):
    # Atomic transfer posting
    reference=data['reference'].strip()
    if not reference or len(reference)>80 or not data['description'].strip():
        raise ValueError('رقم التحويل والبيان مطلوبان (رقم التحويل بحد أقصى 80 حرفاً)')
    date.fromisoformat(data['date'])
    src,dst=data['source_cashbox'],data['destination_cashbox']
    sc,dc=data['source_currency'],data['destination_currency']
    if src not in CASHBOXES or dst not in CASHBOXES or sc not in CURRENCIES or dc not in CURRENCIES:
        raise ValueError('الخزنة أو العملة غير صحيحة')
    if src==dst and sc==dc:
        raise ValueError('لا يمكن التحويل إلى نفس الخزنة وبنفس العملة')
    source_amount=parse_positive(data['source_amount'],'المبلغ')
    if source_amount.as_tuple().exponent < -2:
        raise ValueError('مبلغ الخصم يجب ألا يتجاوز منزلتين عشريتين')
    rate=parse_positive(data['rate'],'سعر الصرف','999999999999.999999')
    if rate.as_tuple().exponent < -6:
        raise ValueError('سعر الصرف بحد أقصى 6 منازل عشرية')
    if sc==dc and rate!=1:
        raise ValueError('التحويل بنفس العملة يتطلب سعر صرف 1')
    destination_amount=(source_amount*rate).quantize(Decimal('0.01'),rounding=ROUND_HALF_UP)
    if destination_amount<=0 or destination_amount>Decimal('999999999999.99'):
        raise ValueError('المبلغ المستلم بعد التقريب غير صالح')
    outref=f'TRF:{reference}:OUT'
    inref=f'TRF:{reference}:IN'
    with connection() as con:
        con.execute('BEGIN IMMEDIATE')
        if con.execute('SELECT 1 FROM transfers WHERE reference=?',(reference,)).fetchone():
            raise ValueError('رقم التحويل مستخدم من قبل')
        if con.execute('SELECT 1 FROM movements WHERE reference IN (?,?)',(outref,inref)).fetchone():
            raise ValueError('رقم التحويل يتعارض مع رقم حركة سابق')
        require_open_day(con,data['date'],src,sc)
        require_open_day(con,data['date'],dst,dc)
        # Never allow a manual movement to reuse the transfer namespace.
        existing=con.execute('SELECT direction,amount FROM movements WHERE cashbox=? AND currency=?',(src,sc)).fetchall()
        balance=sum((1 if direction=='وارد' else -1)*int((Decimal(str(value))*100).quantize(Decimal('1'),rounding=ROUND_HALF_UP)) for direction,value in existing)
        if int(source_amount*100)>balance:
            raise ValueError(f'الرصيد غير كافٍ. المتاح {format_money(Decimal(balance)/100)} {sc}')
        con.execute('''INSERT INTO transfers(reference,tx_date,source_cashbox,destination_cashbox,source_currency,destination_currency,source_amount,rate,destination_amount,description,created_by)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)''',(reference,data['date'],src,dst,sc,dc,str(source_amount),str(rate),str(destination_amount),data['description'].strip(),username))
        for box,currency,direction,amount,ref in ((src,sc,'منصرف',source_amount,outref),(dst,dc,'وارد',destination_amount,inref)):
            con.execute('''INSERT INTO movements(tx_date,cashbox,currency,direction,amount,description,created_by,reference)
                VALUES(?,?,?,?,?,?,?,?)''',(data['date'],box,currency,direction,float(amount),f"تحويل {reference}: {data['description'].strip()}",username,ref))
        con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',(username,'CREATE_TRANSFER',f'{reference}: {src} {source_amount} {sc} -> {dst} {destination_amount} {dc} @ {rate}'))
        invoice_id=data.get('halfa_invoice_id')
        if invoice_id:       # V16: the received amount settles a Halfa purchase invoice (deducted as goods value)
            import pmp_factory
            row=con.execute("SELECT total FROM halfa_invoices WHERE id=? AND status='active'",(invoice_id,)).fetchone()
            if not row: raise ValueError('فاتورة حلفا غير موجودة أو ملغاة')
            paid=sum((Decimal(r[0]) for r in con.execute('SELECT amount FROM halfa_settlements WHERE invoice_id=?',(invoice_id,))),Decimal('0'))
            wanted=str(data.get('halfa_amount') or '').strip()
            settle=Decimal(wanted) if wanted else min(Decimal(row[0])-paid,destination_amount)
            pmp_factory.apply_settlement(con,invoice_id,reference,settle,data['date'],username,dst,dc,destination_amount)
    return source_amount,destination_amount


# V7: preserve original movements; approved changes post compensating entries atomically.
MOVEMENT_KEYS=('tx_date','cashbox','currency','direction','amount','description','reference','created_by')

def _movement(con, movement_id):
    row=con.execute('SELECT '+','.join(MOVEMENT_KEYS)+' FROM movements WHERE id=?',(movement_id,)).fetchone()
    if row is None:
        raise ValueError('الحركة غير موجودة')
    return dict(zip(MOVEMENT_KEYS,row))

def _cents(value):
    return int((Decimal(str(value))*100).quantize(Decimal('1'),rounding=ROUND_HALF_UP))

def _balances(con):
    balances={}
    for box,cur,direction,amount in con.execute('SELECT cashbox,currency,direction,amount FROM movements'):
        key=(box,cur)
        balances[key]=balances.get(key,0)+(1 if direction=='وارد' else -1)*_cents(amount)
    return balances

def _effect(row):
    return (1 if row['direction']=='وارد' else -1)*_cents(row['amount'])

def submit_change(movement_id, request_type, proposed, reason, username):
    if request_type not in ('correct','cancel') or not reason.strip():
        raise ValueError('نوع الطلب والسبب مطلوبان')
    with connection() as con:
        con.execute('BEGIN IMMEDIATE')
        original=_movement(con,movement_id)
        if con.execute('SELECT 1 FROM movement_changes WHERE movement_id=?',(movement_id,)).fetchone():
            raise ValueError('الحركة سبق تصحيحها أو إلغاؤها')
        if con.execute("SELECT 1 FROM change_requests WHERE movement_id=? AND status='pending'",(movement_id,)).fetchone():
            raise ValueError('يوجد طلب معلّق لهذه الحركة')
        ref=original['reference'] or ''
        if ref.startswith('V7:'):
            raise ValueError('لا يمكن تقديم طلب على قيد تسوية')
        if ref.startswith('INV:'):
            raise ValueError('هذه الحركة مرتبطة بفاتورة؛ استخدم شاشة الفواتير (عكس الدفعة أو إلغاء الفاتورة)')
        if request_type=='correct':
            if ref.startswith('TRF:'):
                raise ValueError('تصحيح التحويلات غير متاح حالياً؛ استخدم الإلغاء ثم تحويل جديد')
            proposed=validate_proposal(proposed)
        else:
            proposed=None
        con.execute('''INSERT INTO change_requests(movement_id,request_type,proposed_json,reason,requested_by,original_json)
            VALUES(?,?,?,?,?,?)''',(movement_id,request_type,json.dumps(proposed,ensure_ascii=False) if proposed else None,reason.strip(),username,json.dumps(original,ensure_ascii=False)))
        con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',(username,'REQUEST_CHANGE',f'الحركة {movement_id}: {request_type}؛ {reason.strip()}'))

def validate_proposal(data):
    if not isinstance(data,dict):
        raise ValueError('بيانات التصحيح غير صحيحة')
    result={k:str(data.get(k,'')).strip() for k in ('tx_date','cashbox','currency','direction','amount','description')}
    date.fromisoformat(result['tx_date'])
    if result['cashbox'] not in CASHBOXES or result['currency'] not in CURRENCIES or result['direction'] not in ('وارد','منصرف'):
        raise ValueError('الخزنة أو العملة أو نوع الحركة غير صحيح')
    amount=parse_positive(result['amount'],'المبلغ')
    if amount.as_tuple().exponent < -2:
        raise ValueError('المبلغ بحد أقصى منزلتين عشريتين')
    if not result['description']:
        raise ValueError('البيان مطلوب')
    result['amount']=str(amount)
    return result

def decide_change(request_id, approve, username, role, note=''):
    if role not in ('manager','deputy'):
        raise PermissionError('الاعتماد والرفض للمدير ونائب المدير فقط')
    with connection() as con:
        con.execute('BEGIN IMMEDIATE')
        req=con.execute('SELECT movement_id,request_type,proposed_json,status,original_json FROM change_requests WHERE id=?',(request_id,)).fetchone()
        if not req or req[3]!='pending':
            raise ValueError('الطلب غير موجود أو تمت معالجته')
        mid,kind,proposed_json,_,snapshot_json=req
        original=_movement(con,mid)
        if json.loads(snapshot_json)!=original:
            raise ValueError('الحركة الأصلية تغيرت؛ يجب مراجعة الطلب')
        if con.execute('SELECT 1 FROM movement_changes WHERE movement_id=?',(mid,)).fetchone():
            raise ValueError('تمت معالجة هذه الحركة سابقاً')
        replacement_id=None
        if approve:
            ref=original['reference'] or ''
            ids=[mid]
            if ref.startswith('TRF:'):
                if kind!='cancel':
                    raise ValueError('تصحيح التحويل غير متاح')
                # Resolve both legs through the transfer's exact reference.
                transfer_ref=ref[4:].rsplit(':',1)[0]
                transfer=con.execute('SELECT id FROM transfers WHERE reference=?',(transfer_ref,)).fetchone()
                if not transfer:
                    raise ValueError('سجل التحويل الأصلي غير موجود')
                rows=con.execute('SELECT id FROM movements WHERE reference IN (?,?)',(f'TRF:{transfer_ref}:OUT',f'TRF:{transfer_ref}:IN')).fetchall()
                if len(rows)!=2:
                    raise ValueError('طرفا التحويل غير مكتملين')
                ids=[r[0] for r in rows]
                if any(con.execute('SELECT 1 FROM movement_changes WHERE movement_id=?',(i,)).fetchone() for i in ids):
                    raise ValueError('التحويل سبق تعديله')
                if any(con.execute("SELECT 1 FROM change_requests WHERE movement_id=? AND status='pending' AND id<>?",(i,request_id)).fetchone() for i in ids):
                    raise ValueError('يوجد طلب آخر معلّق على التحويل')
            originals=[(i,_movement(con,i)) for i in ids]
            proposed=validate_proposal(json.loads(proposed_json)) if kind=='correct' else None
            balances=_balances(con)
            for _,item in originals:
                key=(item['cashbox'],item['currency'])
                balances[key]=balances.get(key,0)-_effect(item)
            if proposed:
                key=(proposed['cashbox'],proposed['currency'])
                balances[key]=balances.get(key,0)+_effect(proposed)
            # The reversal posts today, and the corrected entry posts on its chosen date.
            # Also protect closed dates of the original records against retrospective changes.
            for _,item in originals:
                require_open_day(con,item['tx_date'],item['cashbox'],item['currency'])
                require_open_day(con,date.today().isoformat(),item['cashbox'],item['currency'])
            if proposed:
                require_open_day(con,proposed['tx_date'],proposed['cashbox'],proposed['currency'])
            negatives=[(k,v) for k,v in balances.items() if v<0]
            if negatives:
                (box,cur),value=negatives[0]
                raise ValueError(f'لا يمكن الاعتماد: سيصبح رصيد {box} / {cur} سالباً ({format_money(Decimal(value)/100)})')
            for i,item in originals:
                inverse='منصرف' if item['direction']=='وارد' else 'وارد'
                con.execute('''INSERT INTO movements(tx_date,cashbox,currency,direction,amount,description,created_by,reference)
                    VALUES(?,?,?,?,?,?,?,?)''',(date.today().isoformat(),item['cashbox'],item['currency'],inverse,item['amount'],f'تسوية V7 للطلب {request_id} / الحركة {i}',username,f'V7:{request_id}:REV:{i}'))
            if proposed:
                cur=con.execute('''INSERT INTO movements(tx_date,cashbox,currency,direction,amount,description,created_by,reference)
                    VALUES(?,?,?,?,?,?,?,?)''',(proposed['tx_date'],proposed['cashbox'],proposed['currency'],proposed['direction'],float(Decimal(proposed['amount'])),proposed['description'],username,f'V7:{request_id}:NEW'))
                replacement_id=cur.lastrowid
            for i,_ in originals:
                con.execute('INSERT INTO movement_changes(movement_id,request_id,action,replacement_movement_id) VALUES(?,?,?,?)',(i,request_id,kind,replacement_id))
        status='approved' if approve else 'rejected'
        con.execute('''UPDATE change_requests SET status=?,decided_by=?,decision_note=?,decided_at=CURRENT_TIMESTAMP WHERE id=?''',(status,username,note.strip(),request_id))
        con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',(username,'APPROVE_CHANGE' if approve else 'REJECT_CHANGE',f'الطلب {request_id} / الحركة {mid} / {kind} / اعتماد ذاتي مسموح'))


# ---------------------------------------------------------------------------
# V7.9.2: customers and invoices.
# Invoices never touch the cashboxes by themselves. Only a recorded payment posts
# an incoming movement (reference "INV:<invoice>:P<n>"); reversing a payment posts
# an outgoing movement ("INV:<invoice>:R<id>"). Originals are never deleted.
# ---------------------------------------------------------------------------
INVOICE_STATUS_LABELS = {'cancelled': 'ملغاة', 'unpaid': 'غير مسددة', 'partial': 'مسددة جزئياً', 'paid': 'مسددة'}

def _dec(value):
    return Decimal(str(value))

def parse_money_2(value, label, allow_zero=False, maximum='999999999999.99'):
    try:
        amount = Decimal(str(value).strip().replace(',', ''))
    except (InvalidOperation, ValueError):
        raise ValueError(f'{label}: أدخل رقماً صحيحاً')
    if not amount.is_finite() or amount < 0 or (amount == 0 and not allow_zero) or amount > Decimal(maximum):
        raise ValueError(f'{label}: القيمة خارج الحدود المسموحة')
    if amount.as_tuple().exponent < -2:
        raise ValueError(f'{label}: بحد أقصى منزلتين عشريتين')
    return amount

def parse_quantity(value, label='الكمية'):
    try:
        qty = Decimal(str(value).strip().replace(',', ''))
    except (InvalidOperation, ValueError):
        raise ValueError(f'{label}: أدخل رقماً صحيحاً')
    if not qty.is_finite() or qty <= 0 or qty > Decimal('999999.999'):
        raise ValueError(f'{label}: القيمة خارج الحدود المسموحة')
    if qty.as_tuple().exponent < -3:
        raise ValueError(f'{label}: بحد أقصى 3 منازل عشرية')
    return qty

def _balance_cents(con, cashbox, currency):
    rows = con.execute('SELECT direction,amount FROM movements WHERE cashbox=? AND currency=?', (cashbox, currency)).fetchall()
    return sum((1 if d == 'وارد' else -1) * int((Decimal(str(a)) * 100).quantize(Decimal('1'), rounding=ROUND_HALF_UP)) for d, a in rows)

def _clean_customer(data):
    name = ' '.join(str(data.get('name', '')).split())
    phone = str(data.get('phone', '')).strip()
    address = str(data.get('address', '')).strip()
    notes = str(data.get('notes', '')).strip()
    if not name:
        raise ValueError('اسم العميل مطلوب')
    if len(name) > 100 or len(phone) > 40 or len(address) > 200 or len(notes) > 300:
        raise ValueError('أحد الحقول أطول من الحد المسموح')
    return name, phone, address, notes

def create_customer(data, username):
    name, phone, address, notes = _clean_customer(data)
    try:
        with connection() as con:
            cur = con.execute('INSERT INTO customers(name,phone,address,notes,created_by) VALUES(?,?,?,?,?)',
                              (name, phone, address, notes, username))
            con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
                        (username, 'CREATE_CUSTOMER', f'العميل {cur.lastrowid}: {name}'))
            return cur.lastrowid
    except sqlite3.IntegrityError:
        raise ValueError('يوجد عميل بنفس الاسم ورقم الهاتف')

def update_customer(customer_id, data, username):
    name, phone, address, notes = _clean_customer(data)
    try:
        with connection() as con:
            cur = con.execute('UPDATE customers SET name=?,phone=?,address=?,notes=? WHERE id=?',
                              (name, phone, address, notes, customer_id))
            if cur.rowcount != 1:
                raise ValueError('العميل غير موجود')
            con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
                        (username, 'UPDATE_CUSTOMER', f'العميل {customer_id}: {name}'))
    except sqlite3.IntegrityError:
        raise ValueError('يوجد عميل بنفس الاسم ورقم الهاتف')

def set_customer_active(customer_id, active, username, role):
    if role not in ('manager', 'deputy'):
        raise PermissionError('إيقاف العميل أو تفعيله للمدير ونائب المدير فقط')
    with connection() as con:
        cur = con.execute('UPDATE customers SET active=? WHERE id=?', (1 if active else 0, customer_id))
        if cur.rowcount != 1:
            raise ValueError('العميل غير موجود')
        con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
                    (username, 'ACTIVATE_CUSTOMER' if active else 'DEACTIVATE_CUSTOMER', f'العميل {customer_id}'))

def stock_balance(con, warehouse_id, product_id):
    rows = con.execute('SELECT kind,quantity FROM stock_moves WHERE warehouse_id=? AND product_id=?',
                       (warehouse_id, product_id))
    return sum((Decimal(q) if kind in ('IN','RETURN') else -Decimal(q) for kind,q in rows), Decimal('0'))


def save_warehouse(name, username, warehouse_id=None):
    name=' '.join(str(name).split())
    if not name or len(name)>90: raise ValueError('اسم المخزن مطلوب (حتى 90 حرف)')
    with connection() as con:
        if warehouse_id:
            row=con.execute('UPDATE warehouses SET name=? WHERE id=?',(name,warehouse_id))
            if not row.rowcount: raise ValueError('المخزن غير موجود')
            action='RENAME_WAREHOUSE'
        else:
            con.execute('INSERT INTO warehouses(name) VALUES(?)',(name,))
            action='CREATE_WAREHOUSE'
        con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',(username,action,name))


def set_warehouse_active(warehouse_id, active, username, role):
    if role not in ('manager','deputy'): raise PermissionError('هذه العملية للمدير ونائب المدير فقط')
    with connection() as con:
        row=con.execute('UPDATE warehouses SET active=? WHERE id=?',(int(active),warehouse_id))
        if not row.rowcount: raise ValueError('المخزن غير موجود')
        con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
                    (username,'WAREHOUSE_STATUS',f'{warehouse_id}: {active}'))



def correct_stock_balance(warehouse_id, product_id, new_quantity, reason, username, role):
    """Manager-only audited stock correction. Never edit or delete previous stock moves."""
    if role != 'manager': raise PermissionError('تصحيح الرصيد متاح للمدير فقط')
    reason = str(reason).strip()
    if not reason: raise ValueError('سبب التصحيح مطلوب')
    qty = Decimal(str(new_quantity).strip())
    if not qty.is_finite() or qty < 0 or qty != qty.to_integral_value():
        raise ValueError('الرصيد الجديد يجب أن يكون عدداً صحيحاً غير سالب بالكرتونة')
    with closing(connection()) as con:
        try:
            con.execute('BEGIN IMMEDIATE')
            if not con.execute('SELECT 1 FROM warehouses WHERE id=?',(warehouse_id,)).fetchone():
                raise ValueError('المخزن غير موجود')
            if not con.execute('SELECT 1 FROM products WHERE id=?',(product_id,)).fetchone():
                raise ValueError('الصنف غير موجود')
            old = stock_balance(con,warehouse_id,product_id)
            delta = qty-old
            if delta:
                con.execute("""INSERT INTO stock_moves(warehouse_id,product_id,quantity,kind,note,created_by)
                    VALUES(?,?,?,?,?,?)""",(warehouse_id,product_id,str(abs(delta)),
                    'IN' if delta>0 else 'OUT','تصحيح إداري: '+reason[:170],username))
            con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
                (username,'STOCK_CORRECTION',f'warehouse={warehouse_id} product={product_id} old={old} new={qty} delta={delta} reason={reason[:100]}'))
            con.commit()
            return old,qty
        except Exception:
            con.rollback();raise


def rename_warehouse_manager(warehouse_id, new_name, username, role):
    if role != 'manager': raise PermissionError('تعديل المخازن متاح للمدير فقط')
    return save_warehouse(new_name,username,warehouse_id)


def delete_warehouse_manager(warehouse_id, username, role):
    if role != 'manager': raise PermissionError('حذف المخازن متاح للمدير فقط')
    with closing(connection()) as con:
        try:
            con.execute('BEGIN IMMEDIATE')
            row=con.execute('SELECT name FROM warehouses WHERE id=?',(warehouse_id,)).fetchone()
            if not row:raise ValueError('المخزن غير موجود')
            if con.execute('SELECT 1 FROM stock_moves WHERE warehouse_id=? LIMIT 1',(warehouse_id,)).fetchone():
                raise ValueError('المخزن مرتبط بحركات مخزون؛ لا يمكن حذفه. يمكن إيقافه بدلاً من ذلك')
            if con.execute('SELECT 1 FROM invoice_items WHERE warehouse_id=? LIMIT 1',(warehouse_id,)).fetchone():
                raise ValueError('المخزن مرتبط بفواتير؛ لا يمكن حذفه')
            con.execute('DELETE FROM warehouses WHERE id=?',(warehouse_id,))
            con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
                (username,'DELETE_EMPTY_WAREHOUSE',f'{warehouse_id}: {row[0]}'))
            con.commit()
        except Exception:
            con.rollback();raise


def _pmp_report_pdf(path,title,subtitle,headers,rows,widths=None):
    from reportlab.platypus import SimpleDocTemplate, Table, TableStyle, Paragraph, Spacer, Image
    from reportlab.lib import colors
    from reportlab.lib.pagesizes import A4,landscape
    from reportlab.lib.styles import ParagraphStyle
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    import arabic_reshaper
    from bidi.algorithm import get_display
    fontfile=_arabic_font_file()
    if not fontfile:raise RuntimeError('لم يتم العثور على خط عربي مناسب للتصدير')
    pdfmetrics.registerFont(TTFont('PMPExportArabic',fontfile))
    rtl=lambda value:get_display(arabic_reshaper.reshape(str(value)))
    style=ParagraphStyle('pmpbody',fontName='PMPExportArabic',fontSize=9,leading=15,alignment=1)
    cell=lambda v:Paragraph(rtl(v),style)
    doc=SimpleDocTemplate(path,pagesize=landscape(A4),leftMargin=26,rightMargin=26,topMargin=26,bottomMargin=26)
    story=[]
    logo=APP_DIR/'pmp_logo.png'
    if logo.exists():story.append(Image(str(logo),width=94,height=65,kind='proportional'));story.append(Spacer(1,7))
    story.extend([Paragraph(rtl(title),ParagraphStyle('pmptitle',parent=style,fontSize=17,leading=24)),
        Paragraph(rtl(subtitle),style),Spacer(1,13)])
    data=[[cell(h) for h in headers]]+[[cell(v) for v in row] for row in rows]
    if not rows:data.append([cell('لا توجد بيانات')]+[cell('') for _ in headers[1:]])
    t=Table(data,colWidths=widths or [780/len(headers)]*len(headers),repeatRows=1,hAlign='CENTER')
    t.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),colors.HexColor('#D9E8F6')),
        ('ROWBACKGROUNDS',(0,1),(-1,-1),[colors.white,colors.HexColor('#F0F5FA')]),
        ('GRID',(0,0),(-1,-1),0.35,colors.HexColor('#CEDBE9')),
        ('VALIGN',(0,0),(-1,-1),'MIDDLE'),('TOPPADDING',(0,0),(-1,-1),7),('BOTTOMPADDING',(0,0),(-1,-1),7)]))
    story.append(t);doc.build(story)


def _pmp_report_excel(path,title,subtitle,headers,rows):
    from openpyxl import Workbook
    from openpyxl.styles import Font,PatternFill,Alignment,Border,Side
    from openpyxl.drawing.image import Image as ExcelImage
    from openpyxl.utils import get_column_letter
    wb=Workbook();ws=wb.active;ws.title='PMP Report';ws.sheet_view.rightToLeft=True
    n=len(headers);ws.merge_cells(start_row=1,start_column=1,end_row=1,end_column=n)
    ws.cell(1,1,title);ws.cell(1,1).font=Font(name='Arial',size=17,bold=True,color='164B87')
    ws.merge_cells(start_row=2,start_column=1,end_row=2,end_column=n);ws.cell(2,1,subtitle)
    logo=APP_DIR/'pmp_logo.png'
    if logo.exists():
        img=ExcelImage(str(logo));img.width=95;img.height=60;ws.add_image(img,f'{get_column_letter(n+1)}1')
    for c,h in enumerate(headers,1):
        cell=ws.cell(4,c,h);cell.fill=PatternFill('solid',fgColor='164B87');cell.font=Font(name='Arial',bold=True,color='FFFFFF');cell.alignment=Alignment(horizontal='center')
    for i,row in enumerate(rows,5):
        for j,value in enumerate(row,1):
            cell=ws.cell(i,j,value);cell.alignment=Alignment(horizontal='center',vertical='center',wrap_text=True)
            if i%2==0:cell.fill=PatternFill('solid',fgColor='EDF4FB')
        ws.row_dimensions[i].height=26
    for j,h in enumerate(headers,1):ws.column_dimensions[get_column_letter(j)].width=39 if 'البيان' in h else 23
    ws.freeze_panes='A5';ws.auto_filter.ref=f'A4:{get_column_letter(n)}{max(4,4+len(rows))}'
    wb.save(path)

def save_product(data, username, product_id=None):
    sku=str(data.get('sku','')).strip().upper()
    name=' '.join(str(data.get('name','')).split())
    if not name or len(sku)>50 or len(name)>160: raise ValueError('اللون مطلوب')
    category=str(data.get('category','')).strip()[:80]
    package_weight=str(data.get('package_weight','')).strip()
    if len(package_weight)>50: raise ValueError('وزن العبوة طويل جداً (الحد 50 حرفاً)')
    paint_color=' '.join(str(data.get('paint_color','')).split())
    if len(paint_color)>60: raise ValueError('اسم اللون طويل جداً')
    unit=str(data.get('unit','كرتونة' if paint_color else 'قطعة')).strip()[:40] or ('كرتونة' if paint_color else 'قطعة')
    if paint_color and unit != 'كرتونة':
        raise ValueError('الأصناف ذات اللون في جدول البوهيات يجب أن تكون وحدتها كرتونة')
    purchase=parse_money_2(data.get('purchase_price','0'),'سعر الشراء',allow_zero=True)
    sale=parse_money_2(data.get('sale_price','0'),'سعر البيع',allow_zero=True)
    minimum=Decimal(str(data.get('minimum_stock','0')).strip() or '0')
    if not minimum.is_finite() or minimum<0 or minimum>999999 or minimum.as_tuple().exponent < -3:
        raise ValueError('الحد الأدنى للمخزون غير صحيح')
    with connection() as con:
        if not sku:
            import uuid
            sku='PMP-'+uuid.uuid4().hex[:12].upper()
        values=(sku,name,category,unit,str(purchase),str(sale),str(minimum),package_weight,paint_color)
        if product_id:
            row=con.execute('''UPDATE products SET sku=?,name=?,category=?,unit=?,purchase_price=?,
                sale_price=?,minimum_stock=?,package_weight=?,paint_color=? WHERE id=?''',values+(product_id,))
            if not row.rowcount: raise ValueError('الصنف غير موجود')
        else:
            con.execute('''INSERT INTO products(sku,name,category,unit,purchase_price,sale_price,minimum_stock,package_weight,paint_color)
                VALUES(?,?,?,?,?,?,?,?,?)''',values)
        con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
                    (username,'UPDATE_PRODUCT' if product_id else 'CREATE_PRODUCT',sku))


def manager_update_product_color(product_id, new_color, username, role):
    """Rename a product without changing its identity or historical invoice descriptions."""
    if role != 'manager': raise PermissionError('تعديل اللون متاح للمدير فقط')
    color=' '.join(str(new_color).split())
    if not color or len(color)>60: raise ValueError('أدخل لوناً صحيحاً (حتى 60 حرفاً)')
    with closing(connection()) as con:
        try:
            con.execute('BEGIN IMMEDIATE')
            row=con.execute('SELECT name,paint_color,package_weight,active FROM products WHERE id=?',(product_id,)).fetchone()
            if not row: raise ValueError('الصنف غير موجود')
            if not row[3]: raise ValueError('الصنف موقوف')
            duplicate=con.execute("""SELECT id FROM products WHERE id<>? AND active=1 AND package_weight=?
                AND (paint_color=? OR (TRIM(COALESCE(paint_color,''))='' AND name=?)) LIMIT 1""",
                (product_id,row[2],color,color)).fetchone()
            if duplicate: raise ValueError('يوجد صنف بنفس اللون والوزن؛ لا يمكن إنشاء تكرار')
            con.execute('UPDATE products SET name=?,paint_color=? WHERE id=?',(color,color,product_id))
            con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
                (username,'RENAME_PRODUCT_COLOR',f'{product_id}: {row[1] or row[0]} -> {color}'))
            con.commit()
        except Exception:
            con.rollback();raise


def manager_remove_product(product_id, username, role):
    """Delete only unreferenced products; archive referenced products to protect history."""
    if role != 'manager': raise PermissionError('حذف الأصناف متاح للمدير فقط')
    with closing(connection()) as con:
        try:
            con.execute('BEGIN IMMEDIATE')
            row=con.execute('SELECT name,active FROM products WHERE id=?',(product_id,)).fetchone()
            if not row: raise ValueError('الصنف غير موجود')
            used_moves=con.execute('SELECT 1 FROM stock_moves WHERE product_id=? LIMIT 1',(product_id,)).fetchone()
            used_invoices=con.execute('SELECT 1 FROM invoice_items WHERE product_id=? LIMIT 1',(product_id,)).fetchone()
            if used_moves or used_invoices:
                con.execute('UPDATE products SET active=0 WHERE id=?',(product_id,))
                action='ARCHIVE_PRODUCT'
                result='الصنف مرتبط بحركات أو فواتير؛ تم إيقافه بدلاً من حذفه حفاظاً على السجلات'
            else:
                con.execute('DELETE FROM products WHERE id=?',(product_id,))
                action='DELETE_UNUSED_PRODUCT'
                result='تم حذف الصنف غير المستخدم'
            con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
                (username,action,f'{product_id}: {row[0]}'))
            con.commit()
            return result
        except Exception:
            con.rollback();raise


def receive_stock_unified(warehouse_id, color, weight, quantity, username, note='', purchase='0', sale='0', minimum='0'):
    """Atomic product lookup/create + intake. On any error nothing is saved."""
    color=' '.join(str(color).split())
    weight=' '.join(str(weight).split())
    if not color or not weight:raise ValueError('اللون ووزن العبوة مطلوبان')
    if len(color)>60 or len(weight)>50:raise ValueError('اللون أو وزن العبوة طويل جداً')
    q=parse_quantity(quantity)
    if q!=q.to_integral_value():raise ValueError('الكمية يجب أن تكون كراتين كاملة')
    with closing(connection()) as con:
        try:
            con.execute('BEGIN IMMEDIATE')
            if not con.execute('SELECT 1 FROM warehouses WHERE id=? AND active=1',(warehouse_id,)).fetchone():
                raise ValueError('المخزن غير موجود أو موقوف')
            matches=con.execute("""SELECT id FROM products WHERE active=1 AND package_weight=?
                AND (paint_color=? OR (TRIM(paint_color)='' AND name=?)) ORDER BY id""",(weight,color,color)).fetchall()
            if len(matches)>1:raise ValueError('يوجد أكثر من صنف مطابق لنفس اللون والوزن. يلزم مراجعة الأصناف المكررة قبل التوريد')
            created=not bool(matches)
            if matches:pid=matches[0][0]
            else:
                import uuid
                pp=parse_money_2(purchase,'سعر الشراء',allow_zero=True)
                sp=parse_money_2(sale,'سعر البيع',allow_zero=True)
                mn=Decimal(str(minimum).strip() or '0')
                if not mn.is_finite() or mn<0 or mn!=mn.to_integral_value():raise ValueError('الحد الأدنى يجب أن يكون عدداً صحيحاً غير سالب')
                pid=con.execute("""INSERT INTO products(sku,name,category,unit,purchase_price,sale_price,minimum_stock,package_weight,paint_color)
                    VALUES(?,?,?,?,?,?,?,?,?)""",('PMP-'+uuid.uuid4().hex[:12].upper(),color,'بوهيات','كرتونة',str(pp),str(sp),str(mn),weight,color)).lastrowid
            con.execute("""INSERT INTO stock_moves(warehouse_id,product_id,quantity,kind,note,created_by)
                VALUES(?,?,?,?,?,?)""",(warehouse_id,pid,str(q),'IN',str(note or '')[:200],username))
            con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
                        (username,'UNIFIED_STOCK_IN',f'{warehouse_id}/{pid}: +{q} cartons'))
            balance=stock_balance(con,warehouse_id,pid)
            con.commit()
            return pid,balance,created
        except Exception:
            con.rollback()
            raise

def post_stock_move(warehouse_id, product_id, quantity, kind, note, username):
    if kind not in ('IN','OUT'): raise ValueError('نوع حركة المخزون غير صحيح')
    qty=parse_quantity(quantity)
    if kind not in ('IN', 'OUT'):
        raise ValueError('نوع حركة المخزون غير صحيح')
    with connection() as con:
        con.execute('BEGIN IMMEDIATE')
        color_row=con.execute('SELECT paint_color FROM products WHERE id=?',(product_id,)).fetchone()
        if color_row and color_row[0] and qty != qty.to_integral_value():
            raise ValueError('كمية البوهيات تُسجل بالكرتونة الكاملة فقط')
        if not con.execute('SELECT 1 FROM warehouses WHERE id=? AND active=1',(warehouse_id,)).fetchone():
            raise ValueError('اختر مخزناً نشطاً')
        if not con.execute('SELECT 1 FROM products WHERE id=? AND active=1',(product_id,)).fetchone():
            raise ValueError('اختر صنفاً نشطاً')
        if kind=='OUT' and stock_balance(con,warehouse_id,product_id)<qty:
            raise ValueError('الرصيد المتاح أقل من الكمية المطلوبة')
        con.execute('''INSERT INTO stock_moves(warehouse_id,product_id,quantity,kind,note,created_by)
            VALUES(?,?,?,?,?,?)''',(warehouse_id,product_id,str(qty),kind,str(note or '')[:200],username))
        con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
                    (username,'STOCK_'+kind,f'{warehouse_id}/{product_id}: {qty}'))

def create_invoice(customer_id, invoice_date, currency, items, discount, notes, username, payment=None):
    """Create an invoice with its lines atomically. Returns (invoice_id, invoice_no, total).
    payment (optional): {'amount','cashbox','date','note'} posts an incoming cashbox movement in the SAME transaction;
    if it fails (closed day, bad cashbox, ...) the invoice is not created either."""
    invoice_date = date.fromisoformat(str(invoice_date).strip()).isoformat()
    if currency not in CURRENCIES:
        raise ValueError('العملة غير صحيحة')
    if not items:
        raise ValueError('أضف بنداً واحداً على الأقل للفاتورة')
    if len(items) > 100:
        raise ValueError('الحد الأقصى 100 بند في الفاتورة')
    notes = str(notes or '').strip()
    if len(notes) > 300:
        raise ValueError('الملاحظات أطول من الحد المسموح')
    lines = []
    subtotal = Decimal('0')
    for position, item in enumerate(items, 1):
        desc = ' '.join(str(item.get('description', '')).split())
        if not desc or len(desc) > 200:
            raise ValueError(f'البند {position}: الوصف مطلوب (بحد أقصى 200 حرف)')
        qty = parse_quantity(item.get('quantity'), f'البند {position}: الكمية')
        price = parse_money_2(item.get('unit_price'), f'البند {position}: سعر الوحدة', allow_zero=True)
        line_total = (qty * price).quantize(Decimal('0.01'), rounding=ROUND_HALF_UP)
        subtotal += line_total
        product_id = item.get('product_id')
        warehouse_id = item.get('warehouse_id')
        if bool(product_id) != bool(warehouse_id):
            raise ValueError('يجب تحديد الصنف والمخزن معاً')
        lines.append((desc, qty, price, line_total, int(product_id) if product_id else None,
                      int(warehouse_id) if warehouse_id else None))
    discount_amount = parse_money_2(discount if str(discount).strip() else '0', 'الخصم', allow_zero=True)
    if discount_amount >= subtotal:
        raise ValueError('الخصم يجب أن يكون أقل من إجمالي البنود')
    total = subtotal - discount_amount
    if total > Decimal('999999999999.99'):
        raise ValueError('إجمالي الفاتورة أكبر من الحد المسموح')
    pay = None
    if payment and str(payment.get('amount', '')).strip():
        pay_amount = parse_money_2(payment.get('amount'), 'مبلغ السداد')
        if pay_amount > total:
            raise ValueError(f'مبلغ السداد أكبر من صافي الفاتورة ({format_money(total)} {currency})')
        pay_date = date.fromisoformat(str(payment.get('date') or invoice_date).strip()).isoformat()
        pay_note = str(payment.get('note', '')).strip()
        if len(pay_note) > 200:
            raise ValueError('ملاحظة السداد أطول من الحد المسموح')
        pay = (pay_date, str(payment.get('cashbox', '')), pay_amount, pay_note)
    with connection() as con:
        con.execute('BEGIN IMMEDIATE')
        customer = con.execute('SELECT name,active FROM customers WHERE id=?', (customer_id,)).fetchone()
        if not customer:
            raise ValueError('العميل غير موجود')
        if not customer[1]:
            raise ValueError('العميل موقوف؛ فعّله أولاً لإصدار فاتورة له')
        next_id = con.execute('SELECT COALESCE(MAX(id),0)+1 FROM invoices').fetchone()[0]
        invoice_no = f'INV-{next_id:06d}'
        cur = con.execute('''INSERT INTO invoices(invoice_no,customer_id,invoice_date,currency,subtotal,discount,total,notes,created_by)
            VALUES(?,?,?,?,?,?,?,?,?)''', (invoice_no, customer_id, invoice_date, currency, str(subtotal),
                                          str(discount_amount), str(total), notes, username))
        invoice_id = cur.lastrowid
        for desc, qty, price, line_total, product_id, warehouse_id in lines:
            if product_id:
                product=con.execute('SELECT active,paint_color FROM products WHERE id=?',(product_id,)).fetchone()
                if product and product[1] and qty != qty.to_integral_value():
                    raise ValueError('كمية البوهيات في الفاتورة يجب أن تكون كراتين كاملة')
                warehouse=con.execute('SELECT active FROM warehouses WHERE id=?',(warehouse_id,)).fetchone()
                if not product or not product[0] or not warehouse or not warehouse[0]:
                    raise ValueError('الصنف أو المخزن غير نشط')
                if stock_balance(con,warehouse_id,product_id)<qty:
                    raise ValueError(f'المخزون غير كافٍ للبند: {desc}')
            con.execute('''INSERT INTO invoice_items(invoice_id,description,quantity,unit_price,line_total,product_id,warehouse_id)
                VALUES(?,?,?,?,?,?,?)''',(invoice_id,desc,str(qty),str(price),str(line_total),product_id,warehouse_id))
            if product_id:
                con.execute('''INSERT INTO stock_moves(warehouse_id,product_id,quantity,kind,invoice_id,note,created_by)
                    VALUES(?,?,?,'SALE',?,?,?)''',(warehouse_id,product_id,str(qty),invoice_id,invoice_no,username))
        con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
                    (username, 'CREATE_INVOICE', f'{invoice_no}: {customer[0]} / {total} {currency}'))
        if pay:
            _post_invoice_payment(con, invoice_id, pay[0], pay[1], pay[2], pay[3], username)
    return invoice_id, invoice_no, total

def _invoice_paid(con, invoice_id):
    return sum((_dec(a) for (a,) in con.execute("SELECT amount FROM invoice_payments WHERE invoice_id=? AND status='active'", (invoice_id,))), Decimal('0'))

def invoice_status(status, total, paid):
    if status == 'cancelled':
        return 'cancelled'
    if paid <= 0:
        return 'unpaid'
    return 'paid' if paid >= total else 'partial'

def get_invoice(invoice_id):
    """Return a dict with the invoice, its lines, payments and computed paid/balance."""
    with connection() as con:
        head = con.execute('''SELECT i.id,i.invoice_no,i.customer_id,c.name,c.phone,c.address,i.invoice_date,i.currency,
            i.subtotal,i.discount,i.total,i.notes,i.status,COALESCE(i.cancel_reason,''),i.created_by,i.created_at
            FROM invoices i JOIN customers c ON c.id=i.customer_id WHERE i.id=?''', (invoice_id,)).fetchone()
        if not head:
            raise ValueError('الفاتورة غير موجودة')
        keys = ('id', 'invoice_no', 'customer_id', 'customer', 'phone', 'address', 'invoice_date', 'currency', 'subtotal',
                'discount', 'total', 'notes', 'status', 'cancel_reason', 'created_by', 'created_at')
        inv = dict(zip(keys, head))
        inv['items'] = [dict(zip(('description', 'quantity', 'unit_price', 'line_total'), r)) for r in con.execute(
            'SELECT description,quantity,unit_price,line_total FROM invoice_items WHERE invoice_id=? ORDER BY id', (invoice_id,))]
        inv['payments'] = [dict(zip(('id', 'pay_date', 'cashbox', 'amount', 'note', 'status', 'created_by'), r)) for r in con.execute(
            'SELECT id,pay_date,cashbox,amount,note,status,created_by FROM invoice_payments WHERE invoice_id=? ORDER BY id', (invoice_id,))]
        paid = _invoice_paid(con, invoice_id)
    total = _dec(inv['total'])
    inv['paid'] = paid
    inv['balance'] = Decimal('0') if inv['status'] == 'cancelled' else total - paid
    inv['state'] = invoice_status(inv['status'], total, paid)
    return inv

def list_invoices(search='', state='all', customer_id=None):
    """Rows: (id,no,date,customer,currency,total,paid,balance,state)."""
    with connection() as con:
        rows = con.execute('''SELECT i.id,i.invoice_no,i.invoice_date,c.name,i.currency,i.total,i.status,i.customer_id,COALESCE(i.notes,'')
            FROM invoices i JOIN customers c ON c.id=i.customer_id ORDER BY i.id DESC''').fetchall()
        paid_map = {}
        for inv_id, amount in con.execute("SELECT invoice_id,amount FROM invoice_payments WHERE status='active'"):
            paid_map[inv_id] = paid_map.get(inv_id, Decimal('0')) + _dec(amount)
    needle = str(search or '').strip().lower()
    result = []
    for inv_id, no, inv_date, cname, cur, total, status, cust_id, notes in rows:
        total_d = _dec(total)
        paid = paid_map.get(inv_id, Decimal('0'))
        st = invoice_status(status, total_d, paid)
        balance = Decimal('0') if st == 'cancelled' else total_d - paid
        if customer_id and cust_id != customer_id:
            continue
        if state != 'all' and st != state:
            continue
        if needle and needle not in no.lower() and needle not in cname.lower() and needle not in notes.lower():
            continue
        result.append((inv_id, no, inv_date, cname, cur, total_d, paid, balance, st))
    return result

def customer_balances():
    """Outstanding amounts per (customer_id, currency); cancelled invoices are excluded."""
    out = {}
    with connection() as con:
        rows = con.execute("SELECT id,customer_id,currency,total FROM invoices WHERE status='active'").fetchall()
        paid_map = {}
        for inv_id, amount in con.execute("SELECT invoice_id,amount FROM invoice_payments WHERE status='active'"):
            paid_map[inv_id] = paid_map.get(inv_id, Decimal('0')) + _dec(amount)
    for inv_id, cid, cur, total in rows:
        bucket = out.setdefault((cid, cur), {'invoiced': Decimal('0'), 'paid': Decimal('0'), 'balance': Decimal('0')})
        paid = paid_map.get(inv_id, Decimal('0'))
        bucket['invoiced'] += _dec(total)
        bucket['paid'] += paid
        bucket['balance'] += _dec(total) - paid
    return out

def _post_invoice_payment(con, invoice_id, pay_date, cashbox, amount, note, username):
    """Post a payment inside an already-open BEGIN IMMEDIATE transaction. Returns the payment id."""
    head = con.execute('''SELECT i.invoice_no,i.invoice_date,i.currency,i.total,i.status,c.name
        FROM invoices i JOIN customers c ON c.id=i.customer_id WHERE i.id=?''', (invoice_id,)).fetchone()
    if not head:
        raise ValueError('الفاتورة غير موجودة')
    invoice_no, invoice_date, currency, total, status, customer = head
    if status != 'active':
        raise ValueError('لا يمكن تسجيل دفعة على فاتورة ملغاة')
    if cashbox not in CASHBOXES:
        raise ValueError('اختر الخزنة التي سيُسدَّد فيها المبلغ')
    if pay_date < invoice_date:
        raise ValueError('تاريخ الدفعة لا يمكن أن يسبق تاريخ الفاتورة')
    balance = _dec(total) - _invoice_paid(con, invoice_id)
    if amount > balance:
        raise ValueError(f'المبلغ أكبر من المتبقي على الفاتورة ({format_money(balance)} {currency})')
    require_open_day(con, pay_date, cashbox, currency)
    seq = con.execute('SELECT COUNT(*) FROM invoice_payments WHERE invoice_id=?', (invoice_id,)).fetchone()[0] + 1
    reference = f'INV:{invoice_no}:P{seq}'
    desc = f'دفعة فاتورة {invoice_no} - {customer}' + (f' - {note}' if note else '')
    mv = con.execute('''INSERT INTO movements(tx_date,cashbox,currency,direction,amount,description,created_by,reference)
        VALUES(?,?,?,?,?,?,?,?)''', (pay_date, cashbox, currency, 'وارد', float(amount), desc, username, reference))
    cur = con.execute('''INSERT INTO invoice_payments(invoice_id,pay_date,cashbox,amount,note,movement_id,created_by)
        VALUES(?,?,?,?,?,?,?)''', (invoice_id, pay_date, cashbox, str(amount), note, mv.lastrowid, username))
    con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
                (username, 'INVOICE_PAYMENT', f'{invoice_no}: {amount} {currency} -> {cashbox} / الحركة {mv.lastrowid}'))
    return cur.lastrowid

def record_invoice_payment(invoice_id, pay_date, cashbox, amount, note, username):
    """Post an incoming movement for a customer payment. Returns the new payment id."""
    pay_date = date.fromisoformat(str(pay_date).strip()).isoformat()
    amount = parse_money_2(amount, 'مبلغ الدفعة')
    note = str(note or '').strip()
    if len(note) > 200:
        raise ValueError('الملاحظة أطول من الحد المسموح')
    with connection() as con:
        con.execute('BEGIN IMMEDIATE')
        return _post_invoice_payment(con, invoice_id, pay_date, cashbox, amount, note, username)

def reverse_invoice_payment(payment_id, reason, username, role):
    """Reverse a payment with an outgoing movement dated today (the original stays untouched)."""
    if role not in ('manager', 'deputy'):
        raise PermissionError('عكس الدفعات للمدير ونائب المدير فقط')
    if not str(reason or '').strip():
        raise ValueError('سبب العكس مطلوب')
    today = date.today().isoformat()
    with connection() as con:
        con.execute('BEGIN IMMEDIATE')
        row = con.execute('''SELECT p.invoice_id,p.cashbox,p.amount,p.status,i.invoice_no,i.currency
            FROM invoice_payments p JOIN invoices i ON i.id=p.invoice_id WHERE p.id=?''', (payment_id,)).fetchone()
        if not row:
            raise ValueError('الدفعة غير موجودة')
        invoice_id, cashbox, amount, status, invoice_no, currency = row
        if status != 'active':
            raise ValueError('الدفعة معكوسة من قبل')
        amount = _dec(amount)
        require_open_day(con, today, cashbox, currency)
        if int(amount * 100) > _balance_cents(con, cashbox, currency):
            raise ValueError('رصيد الخزنة لا يكفي لعكس هذه الدفعة')
        mv = con.execute('''INSERT INTO movements(tx_date,cashbox,currency,direction,amount,description,created_by,reference)
            VALUES(?,?,?,?,?,?,?,?)''', (today, cashbox, currency, 'منصرف', float(amount),
                                         f'عكس دفعة فاتورة {invoice_no}: {reason.strip()}', username, f'INV:{invoice_no}:R{payment_id}'))
        con.execute('''UPDATE invoice_payments SET status='reversed',reversal_movement_id=?,reversal_reason=?,reversed_by=?,
            reversed_at=CURRENT_TIMESTAMP WHERE id=?''', (mv.lastrowid, reason.strip(), username, payment_id))
        con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
                    (username, 'REVERSE_INVOICE_PAYMENT', f'{invoice_no}: الدفعة {payment_id}؛ {reason.strip()}'))

def cancel_invoice(invoice_id, reason, username, role):
    if role not in ('manager', 'deputy'):
        raise PermissionError('إلغاء الفواتير للمدير ونائب المدير فقط')
    if not str(reason or '').strip():
        raise ValueError('سبب الإلغاء مطلوب')
    with connection() as con:
        con.execute('BEGIN IMMEDIATE')
        row = con.execute('SELECT invoice_no,status FROM invoices WHERE id=?', (invoice_id,)).fetchone()
        if not row:
            raise ValueError('الفاتورة غير موجودة')
        if row[1] != 'active':
            raise ValueError('الفاتورة ملغاة من قبل')
        if _invoice_paid(con, invoice_id) > 0:
            raise ValueError('على الفاتورة دفعات فعّالة؛ اعكس الدفعات أولاً ثم ألغِ الفاتورة')
        # Return all sold stock exactly once, in the same transaction as cancellation.
        for wid,pid,qty in con.execute('''SELECT warehouse_id,product_id,quantity FROM invoice_items
                WHERE invoice_id=? AND product_id IS NOT NULL''',(invoice_id,)).fetchall():
            con.execute('''INSERT INTO stock_moves(warehouse_id,product_id,quantity,kind,invoice_id,note,created_by)
                VALUES(?,?,?,'RETURN',?,?,?)''',(wid,pid,qty,invoice_id,'إلغاء فاتورة '+row[0],username))
        con.execute("UPDATE invoices SET status='cancelled',cancel_reason=?,cancelled_by=?,cancelled_at=CURRENT_TIMESTAMP WHERE id=?",
                    (reason.strip(), username, invoice_id))
        con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
                    (username, 'CANCEL_INVOICE', f'{row[0]}؛ {reason.strip()}'))

def _arabic_font_file():
    return next((str(p) for p in [Path('C:/Windows/Fonts/arial.ttf'), Path('C:/Windows/Fonts/tahoma.ttf'),
                                  Path('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf')] if p.exists()), None)

def export_invoice_pdf(invoice_id, path):
    """Printable A4 invoice (Arabic RTL). Requires reportlab, arabic-reshaper, python-bidi."""
    from reportlab.platypus import SimpleDocTemplate, Table, TableStyle, Paragraph, Spacer
    from reportlab.lib import colors
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.styles import ParagraphStyle
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    import arabic_reshaper
    from bidi.algorithm import get_display
    inv = get_invoice(invoice_id)
    fontfile = _arabic_font_file()
    if not fontfile:
        raise RuntimeError('تعذر العثور على خط عربي مناسب لإنشاء PDF.')
    pdfmetrics.registerFont(TTFont('PMPArabic', fontfile))
    def rtl(v): return get_display(arabic_reshaper.reshape(str(v)))
    style = ParagraphStyle('pmp', fontName='PMPArabic', fontSize=10, leading=15, alignment=2)
    def cell(v): return Paragraph(rtl(v), style)
    doc = SimpleDocTemplate(path, pagesize=A4, rightMargin=30, leftMargin=30, topMargin=34, bottomMargin=34)
    story = [Paragraph(rtl('PMP Paints | فاتورة'), ParagraphStyle('t', parent=style, fontSize=19, leading=28, alignment=1)),
             Spacer(1, 6)]
    state_text = INVOICE_STATUS_LABELS[inv['state']]
    meta = [[cell(inv['invoice_no']), cell('رقم الفاتورة')],
            [cell(inv['invoice_date']), cell('التاريخ')],
            [cell(inv['customer']), cell('العميل')],
            [cell(inv['phone'] or '-'), cell('الهاتف')],
            [cell(inv['currency']), cell('العملة')],
            [cell(state_text), cell('الحالة')]]
    mt = Table(meta, colWidths=[330, 110], hAlign='RIGHT')
    mt.setStyle(TableStyle([('GRID', (0, 0), (-1, -1), 0.25, colors.HexColor('#D6E1EC')),
                            ('BACKGROUND', (1, 0), (1, -1), colors.HexColor('#EDF4FB'))]))
    story += [mt, Spacer(1, 12)]
    data = [[cell(h) for h in ['الإجمالي', 'سعر الوحدة', 'الكمية', 'البيان', '#']]]
    for n, it in enumerate(inv['items'], 1):
        data.append([cell(format_money(it['line_total'])), cell(format_money(it['unit_price'])),
                     cell(format_money(it['quantity'])), cell(it['description']), cell(n)])
    table = Table(data, colWidths=[90, 85, 60, 260, 30], repeatRows=1)
    table.setStyle(TableStyle([('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#2465A0')),
                               ('ROWBACKGROUNDS', (0, 1), (-1, -1), [colors.white, colors.HexColor('#EDF4FB')]),
                               ('GRID', (0, 0), (-1, -1), 0.25, colors.HexColor('#D6E1EC')),
                               ('VALIGN', (0, 0), (-1, -1), 'MIDDLE')]))
    story += [table, Spacer(1, 10)]
    totals = [[cell(f"{format_money(inv['subtotal'])} {inv['currency']}"), cell('الإجمالي قبل الخصم')],
              [cell(f"{format_money(inv['discount'])} {inv['currency']}"), cell('الخصم')],
              [cell(f"{format_money(inv['total'])} {inv['currency']}"), cell('صافي الفاتورة')],
              [cell(f"{format_money(inv['paid'])} {inv['currency']}"), cell('المدفوع')],
              [cell(f"{format_money(inv['balance'])} {inv['currency']}"), cell('المتبقي')]]
    tt = Table(totals, colWidths=[150, 130], hAlign='RIGHT')
    tt.setStyle(TableStyle([('GRID', (0, 0), (-1, -1), 0.25, colors.HexColor('#D6E1EC')),
                            ('BACKGROUND', (1, 0), (1, -1), colors.HexColor('#EDF4FB'))]))
    story.append(tt)
    if inv['notes']:
        story += [Spacer(1, 10), Paragraph(rtl('ملاحظات: ' + inv['notes']), style)]
    if inv['status'] == 'cancelled':
        story += [Spacer(1, 10), Paragraph(rtl('هذه الفاتورة ملغاة: ' + inv['cancel_reason']), style)]
    active = [p for p in inv['payments'] if p['status'] == 'active']
    if active:
        story += [Spacer(1, 14), Paragraph(rtl('الدفعات المسجلة'), style)]
        pdata = [[cell(h) for h in ['الخزنة', 'المبلغ', 'التاريخ']]]
        for p in active:
            pdata.append([cell(p['cashbox']), cell(f"{format_money(p['amount'])} {inv['currency']}"), cell(p['pay_date'])])
        pt = Table(pdata, colWidths=[160, 120, 100], hAlign='RIGHT')
        pt.setStyle(TableStyle([('GRID', (0, 0), (-1, -1), 0.25, colors.HexColor('#D6E1EC')),
                                ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#EDF4FB'))]))
        story.append(pt)
    def footer(canvas, document):
        canvas.setFont('PMPArabic', 9)
        canvas.drawCentredString(A4[0] / 2, 18, f'PMP Paints  |  {inv["invoice_no"]}  |  {document.page}')
    doc.build(story, onFirstPage=footer, onLaterPages=footer)



# V8.1 read-only reporting. Never changes the finance or stock ledgers.
def build_v81_report(kind, start='', end='', customer='', product='', warehouse=''):
    if start: date.fromisoformat(start)
    if end: date.fromisoformat(end)
    if start and end and start > end: raise ValueError('تاريخ البداية بعد تاريخ النهاية')
    with connection() as con:
        if kind == 'sales':
            headers=['التاريخ','رقم الفاتورة','العميل','الصنف','وزن العبوة','المخزن','الكمية','سعر الوحدة','إجمالي البند','العملة']
            sql='''SELECT i.invoice_date,i.invoice_no,c.name,ii.description,COALESCE(p.package_weight,''),
                COALESCE(w.name,''),ii.quantity,ii.unit_price,ii.line_total,i.currency
                FROM invoices i JOIN customers c ON c.id=i.customer_id
                JOIN invoice_items ii ON ii.invoice_id=i.id
                LEFT JOIN products p ON p.id=ii.product_id LEFT JOIN warehouses w ON w.id=ii.warehouse_id
                WHERE i.status='active' AND (?='' OR i.invoice_date>=?) AND (?='' OR i.invoice_date<=?)
                AND (?='' OR c.name LIKE ?) AND (?='' OR ii.description LIKE ?)
                AND (?='' OR COALESCE(w.name,'') LIKE ?) ORDER BY i.invoice_date,i.id,ii.id'''
            params=(start,start,end,end,customer,'%'+customer+'%',product,'%'+product+'%',warehouse,'%'+warehouse+'%')
            rows=con.execute(sql,params).fetchall()
            totals={}
            for r in rows: totals[r[9]]=totals.get(r[9],Decimal('0'))+_dec(r[8])
        elif kind == 'debts':
            headers=['التاريخ','رقم الفاتورة','العميل','العملة','إجمالي الفاتورة','المسدد','المتبقي']
            sql='''SELECT i.invoice_date,i.invoice_no,c.name,i.currency,i.total,
                COALESCE((SELECT SUM(CAST(ip.amount AS REAL)) FROM invoice_payments ip
                WHERE ip.invoice_id=i.id AND ip.status='active'),0)
                FROM invoices i JOIN customers c ON c.id=i.customer_id
                WHERE i.status='active' AND (?='' OR i.invoice_date>=?) AND (?='' OR i.invoice_date<=?)
                AND (?='' OR c.name LIKE ?) ORDER BY i.invoice_date,i.id'''
            raw=con.execute(sql,(start,start,end,end,customer,'%'+customer+'%')).fetchall()
            # Recompute payments from their exact decimal strings (no float rounding).
            rows=[];totals={}
            paid_by_no={}
            for no,amount in con.execute('''SELECT i.invoice_no,ip.amount FROM invoice_payments ip
                    JOIN invoices i ON i.id=ip.invoice_id WHERE ip.status='active' '''):
                paid_by_no[no]=paid_by_no.get(no,Decimal('0'))+_dec(amount)
            for d,no,c,cur,total,_ in raw:
                paid=paid_by_no.get(no,Decimal('0')); due=_dec(total)-paid
                if due>0:
                    rows.append((d,no,c,cur,total,str(paid),str(due)))
                    totals[cur]=totals.get(cur,Decimal('0'))+due
        elif kind == 'statement':
            headers=['التاريخ','العميل','العملية','المرجع','العملة','مدين','دائن']
            rows=[];totals={}
            inv=con.execute('''SELECT i.id,i.invoice_date,c.name,i.invoice_no,i.currency,i.total
                FROM invoices i JOIN customers c ON c.id=i.customer_id
                WHERE i.status='active' AND (?='' OR c.name LIKE ?)''',(customer,'%'+customer+'%')).fetchall()
            for iid,d,c,no,cur,total in inv:
                if (not start or d>=start) and (not end or d<=end):
                    rows.append((d,c,'فاتورة',no,cur,total,'0'))
                pays=con.execute("SELECT pay_date,amount FROM invoice_payments WHERE invoice_id=? AND status='active'",(iid,)).fetchall()
                for pd,amt in pays:
                    if (not start or pd>=start) and (not end or pd<=end):
                        rows.append((pd,c,'سداد',no,cur,'0',amt))
            rows.sort(key=lambda r:(r[0],r[1],r[3],r[2]))
            for r in rows:
                cur=r[4];totals[cur]=totals.get(cur,Decimal('0'))+_dec(r[5])-_dec(r[6])
        elif kind == 'stock':
            headers=['التاريخ','المخزن','الصنف','وزن العبوة','نوع الحركة','الكمية','المرجع']
            sql='''SELECT substr(m.created_at,1,10),w.name,p.name,p.package_weight,m.kind,m.quantity,
                COALESCE(i.invoice_no,m.note) FROM stock_moves m
                JOIN warehouses w ON w.id=m.warehouse_id JOIN products p ON p.id=m.product_id
                LEFT JOIN invoices i ON i.id=m.invoice_id
                WHERE (?='' OR substr(m.created_at,1,10)>=?) AND (?='' OR substr(m.created_at,1,10)<=?)
                AND (?='' OR p.name LIKE ?) AND (?='' OR w.name LIKE ?) ORDER BY m.created_at,m.id'''
            rows=con.execute(sql,(start,start,end,end,product,'%'+product+'%',warehouse,'%'+warehouse+'%')).fetchall()
            totals={}
            for r in rows:
                sign=1 if r[4] in ('IN','RETURN') else -1
                key=(r[1],r[2],r[3]);totals[key]=totals.get(key,Decimal('0'))+sign*_dec(r[5])
        else: raise ValueError('نوع التقرير غير معروف')
    return headers,rows,totals


def export_v81_pdf(path,title,headers,rows,summary):
    from reportlab.platypus import SimpleDocTemplate,LongTable,TableStyle,Paragraph,Spacer
    from reportlab.lib import colors
    from reportlab.lib.pagesizes import A4,landscape
    from reportlab.lib.styles import ParagraphStyle
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    import arabic_reshaper
    from bidi.algorithm import get_display
    fontfile=_arabic_font_file()
    if not fontfile: raise RuntimeError('لا يوجد خط عربي مناسب لتصدير PDF')
    pdfmetrics.registerFont(TTFont('PMPReportArabic',fontfile))
    style=ParagraphStyle('pmpreport',fontName='PMPReportArabic',fontSize=7,leading=12,alignment=1)
    title_style=ParagraphStyle('pmpreporttitle',parent=style,fontSize=15,leading=23)
    def cell(value):
        return Paragraph(get_display(arabic_reshaper.reshape(str(value if value is not None else ''))),style)
    doc=SimpleDocTemplate(path,pagesize=landscape(A4),rightMargin=20,leftMargin=20,topMargin=25,bottomMargin=25)
    story=[Paragraph(get_display(arabic_reshaper.reshape('PMP Paints | '+title)),title_style),Spacer(1,9),
           cell('تاريخ التقرير: '+datetime.now().strftime('%Y-%m-%d %H:%M')),Spacer(1,10)]
    for line in summary:
        story.append(cell(line))
    story.append(Spacer(1,9))
    data=[[cell(h) for h in headers]]+[[cell(v) for v in row] for row in rows]
    table=LongTable(data,repeatRows=1,hAlign='CENTER',colWidths=[(landscape(A4)[0]-40)/len(headers)]*len(headers))
    table.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),colors.HexColor('#DCE8F5')),
        ('GRID',(0,0),(-1,-1),0.3,colors.HexColor('#B7C6D7')),
        ('VALIGN',(0,0),(-1,-1),'MIDDLE'),('BOTTOMPADDING',(0,0),(-1,-1),6)]))
    story.append(table)
    doc.build(story)


def export_v81_excel(path,title,headers,rows,summary):
    from openpyxl import Workbook
    from openpyxl.styles import Font,PatternFill,Alignment
    from openpyxl.utils import get_column_letter
    wb=Workbook();ws=wb.active;ws.title='تقرير PMP';ws.sheet_view.rightToLeft=True
    ws.append([title]);ws.append(['تاريخ التقرير: '+datetime.now().strftime('%Y-%m-%d %H:%M')])
    for line in summary: ws.append([line])
    ws.append([]);head_row=ws.max_row+1;ws.append(headers)
    for row in rows: ws.append([str(v) if v is not None else '' for v in row])
    for c in ws[head_row]:
        c.fill=PatternFill('solid',fgColor='135BC8');c.font=Font(color='FFFFFF',bold=True)
    for col in range(1,len(headers)+1):
        ws.column_dimensions[get_column_letter(col)].width=23
    ws.freeze_panes=f'A{head_row+1}';ws.auto_filter.ref=f'A{head_row}:{get_column_letter(len(headers))}{ws.max_row}'
    wb.save(path)

def reset_user_password(manager_username, target_username):
    """Manager-only reset; no plaintext passwords are stored or logged."""
    with connection() as con:
        manager = con.execute('SELECT role,active FROM users WHERE username=?', (manager_username,)).fetchone()
        target = con.execute('SELECT id,role,active FROM users WHERE username=?', (target_username,)).fetchone()
        if not manager or manager != ('manager', 1):
            raise PermissionError('إعادة التعيين متاحة للمدير فقط')
        if not target or not target[2]:
            raise ValueError('المستخدم غير موجود أو غير نشط')
        if target_username == manager_username:
            raise ValueError('استخدم تغيير كلمة المرور لتعديل كلمة مرور حسابك')
        temporary = secrets.token_urlsafe(15)
        con.execute('UPDATE users SET password_hash=?, must_change_password=1 WHERE username=?',
                    (password_hash(temporary), target_username))
        con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
                    (manager_username, 'RESET_PASSWORD', 'إعادة تعيين كلمة مرور المستخدم: '+target_username))
    return temporary


def change_user_password(username, current_password, new_password):
    if len(new_password) < 12:
        raise ValueError('كلمة المرور الجديدة يجب أن تكون 12 حرفاً على الأقل')
    with connection() as con:
        row = con.execute('SELECT password_hash FROM users WHERE username=? AND active=1', (username,)).fetchone()
        if not row or not verify_password(current_password, row[0]):
            raise ValueError('كلمة المرور الحالية غير صحيحة')
        con.execute('UPDATE users SET password_hash=?,must_change_password=0 WHERE username=?',
                    (password_hash(new_password), username))
        con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
                    (username,'CHANGE_PASSWORD','تغيير كلمة المرور'))


def first_name(display_name):
    return (display_name or '').strip().split()[0] if (display_name or '').strip() else 'مستخدم'


class Login:
    def __init__(self, root):
        self.root = root
        root.title('PMP Paints | تسجيل الدخول')
        import pmp_widgets as W
        root.geometry('1100x790')
        root.minsize(940, 660)
        root.configure(fg_color=M3['background'])
        # Figma "Login / Redesigned": navy brand hero on the left, white login card on the right.
        hero = ctk.CTkFrame(root, width=475, fg_color=M3['nav'], corner_radius=0)
        hero.pack(side='left', fill='y')
        hero.pack_propagate(False)
        hero_text = ctk.CTkFrame(hero, fg_color='transparent')
        hero_text.place(relx=0.5, rely=0.42, anchor='center')
        ctk.CTkLabel(hero_text, text='PMP', font=('Segoe UI', 88, 'bold'), text_color='white').pack()
        ctk.CTkLabel(hero_text, text='PAINTS', font=('Segoe UI', 52, 'bold'), text_color=M3['gold']).pack(pady=(0, 14))
        ctk.CTkLabel(hero_text, text='نظام إدارة الخزن والمحاسبة', font=('Segoe UI', 22, 'bold'),
                     text_color='white').pack()
        ctk.CTkLabel(hero_text, text='إدارة مالية • مخزون • مصنع • إنتاج', font=('Segoe UI', 14),
                     text_color='#B8CBE2').pack(pady=(6, 0))
        stage = ctk.CTkFrame(root, fg_color=M3['background'], corner_radius=0)
        stage.pack(side='right', fill='both', expand=True)
        shell = ctk.CTkFrame(stage, fg_color='white', corner_radius=18, width=512)
        shell.place(relx=0.5, rely=0.5, anchor='center')
        ctk.CTkLabel(shell, text='مرحباً بعودتك', font=('Segoe UI', 30, 'bold'), text_color=M3['ink'],
                     anchor='e').pack(fill='x', padx=52, pady=(40, 2))
        ctk.CTkLabel(shell, text='سجّل الدخول للوصول إلى لوحة التحكم', font=('Segoe UI', 13),
                     text_color=M3['muted'], anchor='e').pack(fill='x', padx=52, pady=(0, 22))
        ctk.CTkLabel(shell, text='اسم المستخدم', font=('Segoe UI', 13, 'bold'),
                     text_color=M3['ink'], anchor='e').pack(fill='x', padx=52)
        self.username = W.field_entry(shell, 'اسم المستخدم', width=406)
        self.username.pack(fill='x', padx=52, pady=(6, 16))
        ctk.CTkLabel(shell, text='كلمة المرور', font=('Segoe UI', 13, 'bold'),
                     text_color=M3['ink'], anchor='e').pack(fill='x', padx=52)
        self.password = W.field_entry(shell, '••••••••', show='•', width=406)
        self.password.pack(fill='x', padx=52, pady=(6, 6))
        ctk.CTkButton(shell, text='نسيت كلمة المرور؟', fg_color='transparent', text_color=M3['primary'],
                      hover_color=M3['surface_dim'], anchor='e', font=('Segoe UI', 12),
                      command=lambda: messagebox.showinfo(
                          'استعادة كلمة المرور', 'يرجى مراجعة مدير النظام لإعادة تعيين كلمة المرور.', parent=self.root)
                      ).pack(fill='x', padx=46, pady=(0, 10))
        W.primary_button(shell, 'تسجيل الدخول', self.login, width=406).pack(padx=52, pady=(4, 18))
        ctk.CTkLabel(shell, text=f'PMP Cash System • Secure Access • {APP_VERSION}', font=('Segoe UI', 11),
                     text_color=M3['muted']).pack(pady=(0, 30))
        self.password.bind('<Return>', lambda _e: self.login())
        self.username.focus_set()

    def login(self):
        username = self.username.get().strip()
        with connection() as con:
            row = con.execute('SELECT username,display_name,role,password_hash,COALESCE(job_title,""),must_change_password FROM users WHERE username=? AND active=1',
                              (username,)).fetchone()
        if not row or not verify_password(self.password.get(), row[3]):
            messagebox.showerror('تعذر الدخول', 'اسم المستخدم أو كلمة المرور غير صحيحين', parent=self.root)
            self.password.delete(0, 'end')
            return
        if row[5]:
            if not self.force_password_change(username):
                return
        log_action(username, 'LOGIN', 'تسجيل الدخول')
        for widget in self.root.winfo_children():
            widget.destroy()
        App(self.root, {'username': row[0], 'display_name': row[1], 'role': row[2], 'job_title': row[4]})


    def force_password_change(self, username):
        dialog = ctk.CTkToplevel(self.root)
        dialog.title('تغيير كلمة المرور المؤقتة')
        dialog.geometry('440x350')
        dialog.transient(self.root)
        dialog.grab_set()
        ctk.CTkLabel(dialog, text='يجب تغيير كلمة المرور قبل الدخول',
                     font=('Segoe UI', 16, 'bold')).pack(pady=18)
        fields = []
        for label in ['كلمة المرور المؤقتة', 'كلمة المرور الجديدة', 'تأكيد كلمة المرور الجديدة']:
            ctk.CTkLabel(dialog, text=label).pack(pady=(5, 0))
            entry = RoundedEntry(dialog, show='*', width=300)
            entry.pack(pady=4)
            fields.append(entry)
        result = {'ok': False}
        def save():
            old, new, confirm = [f.get() for f in fields]
            if new != confirm:
                messagebox.showerror('خطأ', 'كلمتا المرور الجديدتان غير متطابقتين', parent=dialog)
                return
            try:
                change_user_password(username, old, new)
            except ValueError as exc:
                messagebox.showerror('خطأ', str(exc), parent=dialog)
                return
            result['ok'] = True
            dialog.destroy()
        RoundedButton(dialog, text='تغيير كلمة المرور والمتابعة', command=save).pack(pady=14)
        dialog.wait_window()
        return result['ok']


def _compact_amount(value):
    """Short label for chart bars (1.2M / 350K). Display only; never used for accounting."""
    value = float(value)
    for limit, suffix in ((1e9, 'B'), (1e6, 'M'), (1e3, 'K')):
        if abs(value) >= limit:
            return f'{value / limit:.1f}'.rstrip('0').rstrip('.') + suffix
    return f'{value:g}'

def _round_rect(canvas, x1, y1, x2, y2, radius, **kwargs):
    radius = max(0, min(radius, (x2 - x1) / 2, (y2 - y1) / 2))
    points = [x1 + radius, y1, x2 - radius, y1, x2, y1, x2, y1 + radius,
              x2, y2 - radius, x2, y2, x2 - radius, y2, x1 + radius, y2,
              x1, y2, x1, y2 - radius, x1, y1 + radius, x1, y1]
    return canvas.create_polygon(points, smooth=True, **kwargs)

# V13: the ten Figma modules (key, sidebar title, icon, subtitle). Order = Figma sidebar order.
MODULES = (
    ('cash', 'الخزن والحسابات', '◇', 'إدارة الأرصدة والحركات النقدية'),
    ('sales', 'الفواتير والمبيعات', '▤', 'الفواتير والتحصيلات والعملاء'),
    ('stock', 'المخازن والمنتجات', '▣', 'أرصدة المخزون وحركة الأصناف'),
    ('raw', 'المصنع والمواد الخام', '◈', 'مراقبة المواد الخام والاحتياجات'),
    ('production', 'الإنتاج والتعبئة', '▥', 'أوامر التصنيع ومراحل الإنتاج'),
    ('finished', 'المنتج التام والتحميل', '▧', 'استلام الإنتاج وتحميل الشحنات'),
    ('transfers', 'التحويلات والتسويات', '⇄', 'متابعة التحويلات وطلبات التصحيح'),
    ('reports', 'التقارير والتحليلات', '▦', 'تقارير مالية وتشغيلية قابلة للتصفية'),
    ('users', 'المستخدمون والصلاحيات', '♙', 'الحسابات والأدوار وسجل النشاط'),
    ('settings', 'الإعدادات والنسخ الاحتياطي', '⚙', 'إعدادات النظام والبيانات والأمان'),
)
# Figma sub-headings of the internal screens (page subtitle under the big title).
SUBTITLES = {
    'profile_dialog': 'إدارة البيانات الشخصية ومعلومات الاتصال وأمان الحساب',
    'صلاحيات الموظفين': 'توزيع الصلاحيات حسب الدور والقسم مع إمكانية البحث والحفظ',
    'PMP | فحص سلامة البيانات': 'فحص قراءة فقط لقاعدة البيانات دون تغيير أي رصيد',
    'database_health_dialog': 'فحص قراءة فقط لقاعدة البيانات دون تغيير أي رصيد',
    'backup_database': 'إدارة النسخ الاحتياطية واستعادة البيانات',
    'transfer_dialog': 'تحويل مبالغ بين الخزن مع سجل مراجعة',
    'closing_dialog': 'مراجعة الحركة اليومية وإقفال الأرصدة',
    'reports_v81_dialog': 'استعراض الحركات مع الفلاتر والتصدير',
    'cashbox_management_dialog': 'إضافة الخزن وتعديل أسمائها',
    'PMP Paints | المخزن الموحد': 'الأصناف والتوريد والأرصدة',
    'PMP Paints | إدارة المخزون': 'الأرصدة الحالية وحركة المخزون',
}
# Embedded-screen name -> (Figma module title, friendly sub-screen title or None).
SCREEN_NAMES = {
    'transfer_dialog': ('التحويلات والتسويات', 'تحويل بين الخزن والعملات'),
    'changes_dialog': ('التحويلات والتسويات', 'طلبات التصحيح والإلغاء'),
    'statement_dialog': ('الخزن والحسابات', 'كشف حساب الخزنة'),
    'cashbox_management_dialog': ('الخزن والحسابات', 'إدارة الخزن'),
    'closing_dialog': ('الخزن والحسابات', 'الإقفال اليومي والجرد'),
    'reports_v81_dialog': ('التقارير والتحليلات', 'تقارير المبيعات'),
    'price_poster_dialog': ('الفواتير والمبيعات', 'قائمة الأسعار والبوسترات'),
    'PMP Paints | العملاء': ('الفواتير والمبيعات', 'العملاء'),
    'PMP Paints | الفواتير': ('الفواتير والمبيعات', 'إدارة الفواتير'),
    'PMP Paints | فاتورة جديدة': ('الفواتير والمبيعات', 'فاتورة جديدة'),
    'PMP Paints | تفاصيل الفاتورة': ('الفواتير والمبيعات', 'تفاصيل الفاتورة'),
    'PMP Paints | المخزن الموحد': ('المخازن والمنتجات', 'المخزن الموحد'),
    'PMP Paints | إدارة المخزون': ('المخازن والمنتجات', 'المخازن وحركة الأصناف'),
    'profile_dialog': ('المستخدمون والصلاحيات', 'الملف الشخصي'),
    'الإنتاج والتعبئة': ('الإنتاج والتعبئة', None),
    'add_user': ('المستخدمون والصلاحيات', 'إدارة المستخدمين'),
    'reset_password_dialog': ('المستخدمون والصلاحيات', 'إعادة تعيين كلمة المرور'),
    'صلاحيات الموظفين': ('المستخدمون والصلاحيات', 'صلاحيات الموظفين'),
    'show_audit': ('المستخدمون والصلاحيات', 'سجل التدقيق'),
    'backup_database': ('الإعدادات والنسخ الاحتياطي', 'النسخ الاحتياطي والاسترداد'),
    'PMP | فحص سلامة البيانات': ('الإعدادات والنسخ الاحتياطي', 'فحص سلامة البيانات'),
}

class NavButton(ctk.CTkFrame):
    """Figma sidebar row: gold icon on the right, Arabic label, optional chevron on the left."""
    def __init__(self, master, label, icon, command, chevron='', height=44,
                 font=('Segoe UI', 13, 'bold'), **kwargs):
        kwargs.setdefault('fg_color', M3['nav_item'])
        super().__init__(master, corner_radius=11, height=height, **kwargs)
        self.label_text = label
        self.icon_text = icon
        self._command = command
        self._selected = False
        self._collapsed = False
        self._chevron = chevron
        self.pack_propagate(False)
        self.icon_label = ctk.CTkLabel(self, text=icon, width=26, text_color=M3['gold'],
                                       fg_color='transparent', font=('Segoe UI Symbol', 17))
        self.text_label = ctk.CTkLabel(self, text=label, text_color='#D5E1F0', fg_color='transparent',
                                       anchor='e', font=font)
        self.chevron_label = ctk.CTkLabel(self, text=chevron, width=20, text_color='#8FA9C9',
                                          fg_color='transparent', font=('Segoe UI', 11))
        self._layout()
        for widget in (self, self.icon_label, self.text_label, self.chevron_label):
            widget.configure(cursor='hand2')
            widget.bind('<Button-1>', self._clicked, add='+')
            widget.bind('<Enter>', self._enter, add='+')
            widget.bind('<Leave>', self._leave, add='+')

    def _layout(self):
        for widget in (self.icon_label, self.text_label, self.chevron_label):
            widget.pack_forget()
        if self._collapsed:
            self.icon_label.pack(expand=True)
            return
        self.icon_label.pack(side='right', padx=(4, 12))
        if self._chevron:
            self.chevron_label.pack(side='left', padx=(10, 0))
        self.text_label.pack(side='right', fill='x', expand=True, padx=(6, 4))

    def _paint(self, hover=False):
        if self._selected:
            background = M3['nav_active']
        elif hover:
            background = M3['nav_card']
        else:
            background = M3['nav_item']
        self.configure(fg_color=background)
        self.text_label.configure(text_color='#FFFFFF' if self._selected else '#D5E1F0')

    def set_selected(self, selected):
        self._selected = bool(selected)
        self._paint(False)

    def set_collapsed(self, collapsed):
        self._collapsed = bool(collapsed)
        self._layout()

    def set_chevron(self, text):
        self._chevron = text
        self.chevron_label.configure(text=text)
        self._layout()

    def set_command(self, command):
        self._command = command

    def _clicked(self, _event=None):
        if self._command:
            self._command()

    def _enter(self, _event=None):
        if not self._selected:
            self._paint(True)

    def _leave(self, _event=None):
        try:
            px, py = self.winfo_pointerxy()
            if 0 <= px - self.winfo_rootx() < self.winfo_width() and 0 <= py - self.winfo_rooty() < self.winfo_height():
                return   # pointer moved onto a child of this row
        except tk.TclError:
            return
        self._paint(False)

class EmbeddedPage(ctk.CTkFrame):
    """Window-compatible content host; navigation stays in the application root."""
    def __init__(self, master, on_close):
        super().__init__(master, fg_color=M3['background'], corner_radius=0)
        self._on_close = on_close
        self._page_title = ''
    def title(self, value=None):
        if value is not None:
            self._page_title = value
        return self._page_title
    def geometry(self, *_): pass
    def minsize(self, *_): pass
    def transient(self, *_): pass
    def grab_set(self): pass
    def grab_release(self): pass
    def protocol(self, *_): pass
    def destroy(self):
        # Release the actual Tk widgets. Calling only _on_close here left
        # old embedded screens packed beside the next screen.
        super().destroy()

class EmbeddedCard(ctk.CTkFrame):
    """White Figma card that hosts an embedded dialog (same window-like API as EmbeddedPage)."""
    def __init__(self, master, on_close):
        ctk.CTkFrame.__init__(self, master, fg_color='#FFFFFF', corner_radius=18, border_width=0)
        self._on_close = on_close
        self._page_title = ''
    def configure(self, *args, **kwargs):
        kwargs.pop('fg_color', None)          # old dialogs asked for a grey page background
        kwargs.pop('corner_radius', None)
        if kwargs:
            super().configure(**kwargs)
    def title(self, value=None):
        if value is not None:
            self._page_title = value
        return self._page_title
    def geometry(self, *_): pass
    def minsize(self, *_): pass
    def transient(self, *_): pass
    def grab_set(self): pass
    def grab_release(self): pass
    def protocol(self, *_): pass
    def destroy(self):
        outer = self.master                   # closing the card closes the whole embedded page
        super().destroy()
        try:
            if outer is not None and outer.winfo_exists():
                outer.destroy()
        except tk.TclError:
            pass

class App:
    def __init__(self, root, user):
        self.user = user
        self.root = root
        from pmp_branch import load_config
        self.branch_config = load_config(APP_DIR / 'pmp_branch_config.json')
        root.title(f'PMP Cash System {APP_VERSION} | نظام الخزنة والمحاسبة')
        apply_table_theme(root)
        root.geometry('1536x960')
        root.minsize(1050, 680)
        # Root already has its background; avoid reconfiguring after destroying login widgets.
        NAVY = M3['nav']
        BLUE = M3['primary']
        BG = M3['background']
        WHITE = '#FFFFFF'
        MUTED = '#64748B'
        ctk.set_appearance_mode('light')

        # V11.8 Figma layout: floating navy sidebar cards on the right; breadcrumb header and
        # content share the main column on the left.
        PANEL = M3['nav_item']
        SUBPANEL = '#0E2240'
        body = ctk.CTkFrame(root, fg_color=BG, corner_radius=0)
        body.pack(fill='both', expand=True)
        sidebar = ctk.CTkFrame(body, width=272, fg_color=BG, corner_radius=0)
        sidebar.pack(side='right', fill='y')
        sidebar.pack_propagate(False)
        self.sidebar = sidebar
        main_col = ctk.CTkFrame(body, fg_color=BG, corner_radius=0)
        main_col.pack(side='right', fill='both', expand=True)

        # ---- Header: breadcrumb (right), account cluster (left).
        header = ctk.CTkFrame(main_col, fg_color=BG, corner_radius=0, height=66)
        header.pack(fill='x', side='top')
        header.pack_propagate(False)
        # Use PIL/CTkImage: Tk.PhotoImage could fail silently on some Windows setups.
        logo_path = APP_DIR/'pmp_logo.png'
        self.logo = None
        if logo_path.exists():
            try:
                from PIL import Image
                logo_image = Image.open(logo_path).convert('RGB')
                self.logo = ctk.CTkImage(light_image=logo_image, dark_image=logo_image,
                                         size=(155, 57))
                ctk.CTkLabel(header, text='', image=self.logo, fg_color='transparent',
                             width=155, height=57).pack(side='right', padx=(4, 22), pady=4)
            except Exception as exc:
                print('PMP logo loading failed:', exc, file=sys.stderr)
        self.breadcrumb_label = ctk.CTkLabel(header, text='الرئيسية  /  لوحة التحكم', text_color=M3['muted'],
                                             font=('Segoe UI', 13), anchor='e')
        self.breadcrumb_label.pack(side='right', padx=(4, 26))
        user_area = ctk.CTkFrame(header, fg_color='transparent')
        user_area.pack(side='left', padx=22)
        # Health indicator reflects configured server endpoint, not general internet access.
        ctk.CTkButton(user_area, text='تسجيل الخروج', width=110, height=34, corner_radius=10,
                      fg_color='#FFFFFF', hover_color='#E8EEF7', text_color=M3['ink'],
                      border_width=1, border_color=M3['outline'],
                      command=self.logout).pack(side='left', padx=6)
        self.connection_indicator = ctk.CTkLabel(user_area, text='', width=38, height=38, fg_color='transparent')
        self.connection_indicator.pack(side='left', padx=(5, 1))
        self.connection_indicator.bind('<Button-1>', lambda e: self.connection_help())
        self.set_connection_indicator('gray')
        self._start_server_probe()
        # Round profile avatar, with employee initials rather than a stock image.
        avatar = tk.Canvas(user_area, width=39, height=39, bg=BG, highlightthickness=0, bd=0, cursor='hand2')
        avatar.pack(side='left', padx=(5, 4))
        self.profile_avatar = avatar
        self._paint_profile_avatar()
        avatar.bind('<Button-1>', lambda _event: self.profile_dialog())
        employee_name = str(user.get('display_name') or user.get('username') or 'PMP').strip()
        self.profile_name_label = ctk.CTkLabel(user_area, text=f"{first_name(employee_name)}  |  {user.get('job_title') or 'موظف'}",
                     text_color=M3['ink'], font=('Segoe UI', 11, 'bold'), cursor='hand2')
        self.profile_name_label.pack(side='left', padx=5)
        self.profile_name_label.bind('<Button-1>', lambda _event: self.profile_dialog())
        ctk.CTkLabel(user_area, text=APP_VERSION, text_color=M3['muted'],
                     font=('Segoe UI', 10)).pack(side='left', padx=(8, 0))

        # ---- Sidebar: brand card (top), navigation panel (middle), account card (bottom).
        self.sidebar_collapsed = False
        self.sidebar_buttons = []   # NavButton rows (single items and group triggers)
        self.active_sidebar_button = None
        self._expanded_group = None
        self._group_containers = []

        brand = ctk.CTkFrame(sidebar, fg_color=M3['nav'], corner_radius=18, height=92)
        brand.pack(fill='x', padx=8, pady=(4, 8))
        brand.pack_propagate(False)
        self.brand_card = brand
        toggle_button = ctk.CTkButton(brand, text='☰', width=34, height=34, corner_radius=10,
                                      fg_color=PANEL, hover_color=M3['nav_card'], text_color='#C7D6EA',
                                      font=('Segoe UI Symbol', 15), command=lambda: toggle_sidebar())
        brand_text = ctk.CTkFrame(brand, fg_color='transparent')
        ctk.CTkLabel(brand_text, text='PMP PAINTS', font=('Segoe UI', 23, 'bold'),
                     text_color='white', anchor='e').pack(fill='x')
        ctk.CTkLabel(brand_text, text='نظام الإدارة والمحاسبة', font=('Segoe UI', 12),
                     text_color='#C7D6EA', anchor='e').pack(fill='x')
        def layout_brand():
            toggle_button.pack_forget()
            brand_text.pack_forget()
            if self.sidebar_collapsed:
                toggle_button.pack(expand=True)
            else:
                toggle_button.pack(side='left', padx=(14, 0))
                brand_text.pack(side='right', fill='both', expand=True, padx=(8, 20), pady=14)

        role_labels = {'manager': 'صلاحية كاملة', 'deputy': 'نائب المدير', 'accountant': 'محاسب'}
        user_card = ctk.CTkFrame(sidebar, fg_color=M3['nav_card'], corner_radius=12, height=58)
        user_card.pack(side='bottom', fill='x', padx=8, pady=(8, 6))
        user_card.pack_propagate(False)
        user_text = ctk.CTkFrame(user_card, fg_color='transparent')
        ctk.CTkLabel(user_text, text=employee_name, font=('Segoe UI', 14, 'bold'),
                     text_color='white', anchor='e').pack(fill='x')
        ctk.CTkLabel(user_text, text=role_labels.get(user['role'], user['role']), font=('Segoe UI', 11),
                     text_color='#B8CBE2', anchor='e').pack(fill='x')
        user_initial = ctk.CTkLabel(user_card, text=(employee_name[:1] or 'P'), font=('Segoe UI', 17, 'bold'),
                                    text_color='white', fg_color='transparent')
        def layout_user():
            user_text.pack_forget()
            user_initial.pack_forget()
            if self.sidebar_collapsed:
                user_initial.pack(expand=True)
            else:
                user_text.pack(side='right', fill='both', expand=True, padx=(8, 16), pady=8)
        for widget in (user_card, user_text, user_initial) + tuple(user_text.winfo_children()):
            widget.configure(cursor='hand2')
            widget.bind('<Button-1>', lambda _event: self.profile_dialog(), add='+')

        # Figma: the dashboard entry is its own selected pill above the navigation panel.
        dash_holder = ctk.CTkFrame(sidebar, fg_color=PANEL, corner_radius=14)
        dash_holder.pack(fill='x', padx=8, pady=(0, 8))
        # The menu scrolls (mouse wheel or scrollbar) when it is taller than the window.
        menu = ctk.CTkScrollableFrame(sidebar, fg_color=PANEL, corner_radius=18,
                                      scrollbar_fg_color=PANEL, scrollbar_button_color='#2E5C93',
                                      scrollbar_button_hover_color='#4A7DB8')
        menu.pack(fill='both', expand=True, padx=8, pady=0)
        self.sidebar_menu = menu
        layout_brand()
        layout_user()

        def toggle_sidebar():
            self.sidebar_collapsed = not self.sidebar_collapsed
            sidebar.configure(width=76 if self.sidebar_collapsed else 272)
            for btn in self.sidebar_buttons:
                btn.set_collapsed(self.sidebar_collapsed)
            layout_brand()
            layout_user()
            if self.sidebar_collapsed:
                if getattr(self, 'settings_expanded', False):
                    self.settings_expanded = False
                    self.settings_children.pack_forget()
                    self.settings_toggle.set_selected(False)
                    self.settings_toggle.set_chevron('▾')
                if self._expanded_group is not None:
                    old_frame, old_trigger = self._expanded_group[0], self._expanded_group[1]
                    old_frame.pack_forget()
                    old_trigger.set_selected(False)
                    old_trigger.set_chevron('▾')
                    self._expanded_group = None

        # Dedicated content area: embedded pages must NEVER cover the sidebar.
        self.content_host = ctk.CTkFrame(main_col, fg_color=BG, corner_radius=0)
        self.content_host.pack(side='top', fill='both', expand=True)
        self.page = ctk.CTkScrollableFrame(self.content_host, fg_color=BG, corner_radius=0,
                                            scrollbar_button_color='#C5D2E3', scrollbar_button_hover_color='#9FB2CB')
        self.page.pack(fill='both', expand=True)
        self.content_body = body  # Keep existing references compatible.
        self.current_embedded = None
        # Cache only the expensive manager dashboard; transactional pages remain fresh.
        self._dashboard_cache = None
        self.page_history = []
        self._current_route = None
        self._pending_route = None
        self._restoring_route = False
        self.page.grid_columnconfigure(0, weight=1)

        from pmp_access import allowed as page_allowed, editor as page_permission_editor
        def permitted(key):
            return page_allowed(DB, user, key)

        def side_button(label, icon, action, active=False, permission=None, parent=None):
            if permission and not permitted(permission):
                return
            holder = parent or menu
            def navigate():
                if self.active_sidebar_button is not btn:
                    for other in self.sidebar_buttons:
                        other.set_selected(False)
                    btn.set_selected(True)
                    self.active_sidebar_button = btn
                self.page_history.clear()
                self._current_route = None
                if permission and not permitted(permission):
                    messagebox.showerror('الصلاحيات','ليس لديك صلاحية لفتح هذه الصفحة')
                    return
                action()
            btn = NavButton(holder, label, icon, navigate)
            btn.pack(fill='x', padx=8, pady=3)
            self.sidebar_buttons.append(btn)
            if active:
                btn.set_selected(True)
                self.active_sidebar_button = btn

        # Inline accordion groups: expand directly beneath the selected section.
        def group(label, icon, entries):
            entries=[(title,action,key) for title,action,key in entries if key is None or permitted(key)]
            if not entries: return
            children=ctk.CTkFrame(menu, fg_color=SUBPANEL, corner_radius=11)
            def choose(action,key):
                if key and not permitted(key):
                    messagebox.showerror('الصلاحيات','ليس لديك صلاحية لهذه الصفحة');return
                self.page_history.clear()
                self._current_route=None
                action()
            for title,action,key in entries:
                ctk.CTkButton(children,text=title,anchor='e',height=36,corner_radius=9,
                    fg_color='transparent',text_color='#C7D6EA',font=('Segoe UI',12),
                    hover_color=M3['nav_card'],
                    command=lambda a=action,k=key:choose(a,k)).pack(fill='x',padx=(10,8),pady=2)
            def toggle_group():
                if self.sidebar_collapsed: toggle_sidebar()
                if children.winfo_manager():
                    children.pack_forget()
                    trigger.set_selected(False)
                    trigger.set_chevron('▾')
                    self._expanded_group=None
                else:
                    if self._expanded_group is not None:
                        old_frame,old_trigger=self._expanded_group[0],self._expanded_group[1]
                        old_frame.pack_forget()
                        old_trigger.set_selected(False)
                        old_trigger.set_chevron('▾')
                    children.pack(after=trigger,fill='x',padx=8,pady=(0,5))
                    trigger.set_selected(True)
                    trigger.set_chevron('▴')
                    self._expanded_group=(children,trigger,label,icon)
                    # Scroll the sidebar itself to expose the new children when needed.
                    def reveal():
                        try:
                            menu.update_idletasks()
                            canvas=menu._parent_canvas
                            bottom=children.winfo_rooty()+children.winfo_height()
                            visible=canvas.winfo_rooty()+canvas.winfo_height()
                            if bottom>visible:
                                canvas.yview_scroll(max(1,int((bottom-visible)/35)+1),'units')
                        except (AttributeError,tk.TclError):pass
                    self.root.after_idle(reveal)
            trigger=NavButton(menu,label,icon,toggle_group,chevron='▾',height=48)
            trigger.pack(fill='x',padx=8,pady=4)
            self.sidebar_buttons.append(trigger)

        # ---- V13 Figma navigation: ONE flat list with the ten Figma modules.
        # A module is listed only when the user holds at least one permission for it
        # (see _module_actions); hidden modules cannot be opened from the sidebar.
        self._permitted = permitted
        self.nav_buttons = {}
        self.settings_expanded = False       # legacy attributes kept for toggle_sidebar()
        def make_nav(key, title, icon, holder, height):
            def go():
                self.page_history.clear()
                self._current_route = None
                self.open_module(key)
            button = NavButton(holder, title, icon, go, height=height)
            button.pack(fill='x', padx=8, pady=3 if holder is menu else 5)
            self.sidebar_buttons.append(button)
            self.nav_buttons[key] = button

        make_nav('dashboard', 'لوحة التحكم' if user['role'] == 'manager' else 'تسجيل حركة مالية',
                 '▦' if user['role'] == 'manager' else '＋', dash_holder, 46)
        for module_key, module_title, module_icon, _module_subtitle in MODULES:
            if self._module_actions(module_key):
                make_nav(module_key, module_title, module_icon, menu, 48)
        self._select_nav('dashboard')



        def section(title):
            frame = ctk.CTkFrame(self.page, fg_color=WHITE, corner_radius=14,
                                 border_width=1, border_color=M3['outline'])
            frame.grid(sticky='ew', padx=16, pady=(12, 0))
            ctk.CTkLabel(frame, text=title, font=('Segoe UI', 16, 'bold'), text_color=NAVY,
                         anchor='e').pack(fill='x', padx=20, pady=(15, 8))
            return frame

        form = section('تسجيل حركة مالية  |  إدخال آمن ومراجعة قبل الحفظ')
        form_inner = ctk.CTkFrame(form, fg_color='#F5F9FF', corner_radius=20, border_width=1, border_color='#DCE9F7')
        form_inner.pack(fill='x', padx=14, pady=(0, 15))
        for col in range(3): form_inner.grid_columnconfigure(col, weight=1, uniform='fields')
        self.fields = {}
        self.field_errors = {}
        self.field_error_labels = {}
        self.required_fields = {'date', 'cashbox', 'currency', 'direction', 'amount', 'description', 'reference'}
        specs = [('date','التاريخ',date.today().isoformat()),
                 ('cashbox','الخزنة','خزنة السودان'),('currency','العملة','SDG'),
                 ('direction','نوع الحركة','وارد'),('amount','المبلغ',''),
                 ('description','البيان',''),('reference','رقم المستند','')]
        # RTL reading order: first field at right, third at left.
        for idx, (key, label, default) in enumerate(specs):
            cell = ctk.CTkFrame(form_inner, fg_color='transparent')
            cell.grid(row=idx // 3, column=2-(idx % 3), sticky='ew', padx=11, pady=9)
            label_row = ctk.CTkFrame(cell, fg_color='transparent')
            label_row.pack(fill='x', pady=(0, 5))
            if key in self.required_fields:
                ctk.CTkLabel(label_row, text='*', text_color='#C62828',
                             font=('Segoe UI', 13, 'bold'), width=13).pack(side='right')
            ctk.CTkLabel(label_row, text=label, text_color=NAVY, anchor='e',
                         font=('Segoe UI', 11, 'bold')).pack(side='right')
            if key in ('direction','currency','cashbox'):
                values={'direction':['وارد','منصرف'],'currency':list(CURRENCIES),'cashbox':list(CASHBOXES)}[key]
                entry=RoundedCombo(cell, values=values, state='readonly', width=25)
                entry.set(default)
            else:
                entry=RoundedEntry(cell, width=25)
                entry.insert(0, default)
            entry.configure(height=46)
            entry.pack(fill='x')
            self.fields[key] = entry
            if key in self.required_fields:
                entry.configure(border_width=2, border_color='#B8CBE3')
                err_label = ctk.CTkLabel(cell, text='', text_color='#C62828',
                                         font=('Segoe UI', 10), anchor='e', height=17)
                err_label.pack(fill='x', pady=(2, 0))
                self.field_error_labels[key] = err_label
                entry.bind('<KeyRelease>', lambda event, k=key: self._clear_field_error(k), add='+')
        action_cell = ctk.CTkFrame(form_inner, fg_color='transparent')
        # Keep the document-reference input on row 2; buttons belong on a separate row.
        # Previously this frame overlapped the reference cell (row 2, column 2).
        action_cell.grid(row=3, column=0, columnspan=3, sticky='ew', padx=11, pady=(8, 14))
        self.save_button=RoundedButton(action_cell, text='▣   حفظ الحركة', command=self.save)
        self.save_button.pack(side='right', padx=6, fill='x', expand=True)
        RoundedButton(action_cell, text='↶   مسح الحقول', command=self.clear_fields,
                      fg_color='#E6EEF9', hover_color='#D2E2F6', text_color=NAVY).pack(side='right', padx=6, fill='x', expand=True)

        stats=ctk.CTkFrame(self.page, fg_color='transparent')
        stats.grid(sticky='ew',padx=11,pady=(12,0))
        self.stat_labels={}
        for i,(key,title,accent) in enumerate([('count','عدد الحركات','#7255B8'),
                                                ('incoming','إجمالي الوارد','#118866'),
                                                ('outgoing','إجمالي المنصرف','#C64153')]):
            stats.grid_columnconfigure(i,weight=1)
            panel=ctk.CTkFrame(stats,fg_color=WHITE,corner_radius=14,border_width=1,border_color='#DDE6F1')
            panel.grid(row=0,column=i,sticky='ew',padx=5)
            ctk.CTkLabel(panel,text=title,text_color=accent,font=('Segoe UI',11,'bold')).pack(anchor='e',padx=17,pady=(12,0))
            lbl=ctk.CTkLabel(panel,text='—',text_color=NAVY,font=('Segoe UI',15,'bold'))
            lbl.pack(anchor='e',padx=17,pady=(4,14))
            self.stat_labels[key]=lbl
        balance_panel=section('الأرصدة حسب الخزنة والعملة')
        self.balance_cards = ctk.CTkFrame(balance_panel, fg_color='transparent')
        self.balance_cards.pack(fill='x', padx=14, pady=(0, 16))
        self.summary = None  # Balances are now shown in separate cashbox/currency cards.

        table_panel=section('سجل الحركات المالية  |  بحث وتصفية وتصدير')
        # Read-only, live search; filters never modify financial records.
        searchbar = ctk.CTkFrame(table_panel, fg_color='#F7FAFE', corner_radius=12)
        searchbar.pack(fill='x', padx=13, pady=(0, 10))
        self.search_text = tk.StringVar()
        self.filter_cashbox = tk.StringVar(value='كل الخزن')
        self.filter_currency = tk.StringVar(value='كل العملات')
        self.filter_direction = tk.StringVar(value='كل الأنواع')
        self.search_after_id = None
        self.search_entry = RoundedEntry(searchbar, textvariable=self.search_text,
                                          placeholder_text='ابحث برقم المستند أو البيان...')
        self.search_entry.pack(side='right', fill='x', expand=True, padx=8, pady=10)
        self.cashbox_filter_widget = RoundedCombo(searchbar, variable=self.filter_cashbox,
                                  values=['كل الخزن'] + list(CASHBOXES), state='readonly', width=170)
        self.cashbox_filter_widget.pack(side='right', padx=5, pady=10)
        self.currency_filter_widget = RoundedCombo(searchbar, variable=self.filter_currency,
                                  values=['كل العملات'] + list(CURRENCIES), state='readonly', width=125)
        self.currency_filter_widget.pack(side='right', padx=5, pady=10)
        self.direction_filter_widget = RoundedCombo(searchbar, variable=self.filter_direction,
                                  values=['كل الأنواع', 'وارد', 'منصرف'], state='readonly', width=115)
        self.direction_filter_widget.pack(side='right', padx=5, pady=10)
        RoundedButton(searchbar, text='مسح البحث والفلاتر', command=self.reset_search,
                      width=145, fg_color='#E6EEF9', hover_color='#D2E2F6',
                      text_color=NAVY).pack(side='right', padx=5, pady=10)
        exportbar = ctk.CTkFrame(table_panel, fg_color='transparent')
        exportbar.pack(fill='x', padx=16, pady=(0, 9))
        ctk.CTkLabel(exportbar, text='التصدير يشمل جميع الحركات المطابقة للبحث والفلاتر',
                     text_color='#526A86', font=('Segoe UI', 10)).pack(side='right', padx=10)
        RoundedButton(exportbar, text='تصدير Excel', width=145,
                      command=self.export_movements_excel).pack(side='left', padx=5)
        RoundedButton(exportbar, text='تصدير PDF', width=145,
                      command=self.export_movements_pdf).pack(side='left', padx=5)
        self.search_text.trace_add('write', self.schedule_search)
        self.filter_cashbox.trace_add('write', self.schedule_search)
        self.filter_currency.trace_add('write', self.schedule_search)
        self.filter_direction.trace_add('write', self.schedule_search)
        tableframe=ctk.CTkFrame(table_panel,fg_color='white')
        tableframe.pack(fill='x',padx=13,pady=(0,16))
        columns=('id','reference','date','cashbox','currency','direction','amount','description')
        style=ttk.Style()
        style.configure('PMP.Treeview',rowheight=40,font=('Segoe UI',11),background='white',fieldbackground='white', foreground=M3['ink'], borderwidth=0)
        style.configure('PMP.Treeview.Heading',font=('Segoe UI',11,'bold'), background=M3['table_head'], foreground=M3['ink'], relief='flat')
        style.map('PMP.Treeview', background=[('selected', '#C9E0FC')],
                  foreground=[('selected', '#092C56')])
        self.tree=ttk.Treeview(tableframe,columns=columns,show='headings',height=11,style='PMP.Treeview')
        for key,title,width in [('id','رقم',55),('reference','رقم المستند',130),('date','التاريخ',110),
                                ('cashbox','الخزنة',135),('currency','العملة',65),('direction','النوع',75),
                                ('amount','المبلغ',135),('description','البيان',210)]:
            self.tree.heading(key,text=title)
            self.tree.column(key,width=width,anchor='center',minwidth=50)
        self.tree.tag_configure('incoming', foreground='#08754B', background='#F0FBF5')
        self.tree.tag_configure('outgoing', foreground='#B42335', background='#FFF2F3')
        tableframe.grid_columnconfigure(0, weight=1)
        tableframe.grid_rowconfigure(0, weight=1)
        self.tree.grid(row=0, column=0, sticky='nsew')
        vscroll=ttk.Scrollbar(tableframe,orient='vertical',command=self.tree.yview)
        vscroll.grid(row=0,column=1,sticky='ns')
        hscroll=ttk.Scrollbar(tableframe,orient='horizontal',command=self.tree.xview)
        hscroll.grid(row=1,column=0,sticky='ew')
        self.tree.configure(yscrollcommand=vscroll.set, xscrollcommand=hscroll.set)
        self.refresh()
        self.root.after(120, self.show_home if self.user['role'] == 'manager' else self.show_financial_form)

    # ---- V13 Figma module pages ------------------------------------------------------------
    def _module_actions(self, key):
        """Quick-action buttons of a Figma module, filtered by the user's permissions.
        An empty list means the user may not open the module (it is hidden from the sidebar)."""
        p = self._permitted
        role = self.user['role']
        def gate(permission, label, command):
            return (label, command) if (permission is None or p(permission)) else None
        actions = []
        if key == 'cash':
            actions = [gate(None, '＋  حركة جديدة', self.show_financial_form),
                       gate('transfer', 'تحويل بين الخزن', self.transfer_dialog),
                       gate('closing', 'إقفال يومية', self.closing_dialog),
                       gate(None, 'كشف حساب الخزنة', self.statement_dialog) if role in ('manager', 'deputy') else None,
                       gate('warehouse', 'إدارة الخزن', self.cashbox_management_dialog) if role in ('manager', 'deputy') else None]
        elif key == 'sales':
            actions = [gate('invoices', '＋  فاتورة جديدة', self.new_invoice_dialog),
                       gate('invoices', 'سجل الفواتير والدفعات', self.invoices_dialog),
                       gate('customers', 'العملاء وكشف العميل', self.customers_dialog),
                       gate('reports', 'تقارير المبيعات', self.reports_v81_dialog),
                       gate('prices', 'الأسعار والبوسترات', self.price_poster_dialog)]
        elif key == 'stock':
            actions = [gate('inventory', '＋  المخازن والبضاعة', self.inventory_dialog),
                       gate('prices', 'الأسعار والبوسترات', self.price_poster_dialog)]
        elif key == 'raw':
            actions = [gate('factory_add', '＋  استلام / تسجيل حركة', lambda: self._factory_from(key, 'إضافة منتج')),
                       gate('factory_balances', 'الأرصدة', lambda: self._factory_from(key, 'الأرصدة')),
                       gate('factory_history', 'سجل الحركات', lambda: self._factory_from(key, 'سجل الحركات')),
                       gate('factory_catalog', 'الأصناف والماكينات', lambda: self._factory_from(key, 'الماكينات والأصناف'))]
        elif key == 'production':
            actions = [gate('factory_add', '＋  تسجيل حركة إنتاج', lambda: self._factory_from(key, 'إضافة منتج')),
                       gate('factory_balances', 'الأرصدة', lambda: self._factory_from(key, 'الأرصدة')),
                       gate('factory_history', 'سجل الحركات', lambda: self._factory_from(key, 'سجل الحركات')),
                       gate('factory_catalog', 'الماكينات والأصناف', lambda: self._factory_from(key, 'الماكينات والأصناف'))]
        elif key == 'finished':
            actions = [gate('factory_add', '＋  استلام / تحميل', lambda: self._factory_from(key, 'إضافة منتج')),
                       gate('factory_balances', 'الأرصدة', lambda: self._factory_from(key, 'الأرصدة')),
                       gate('factory_history', 'سجل الحركات', lambda: self._factory_from(key, 'سجل الحركات'))]
        elif key == 'transfers':
            actions = [gate('transfer', '＋  تحويل جديد', self.transfer_dialog),
                       gate('corrections', 'طلبات التصحيح والإلغاء', self.changes_dialog)]
        elif key == 'reports':
            actions = [gate('reports', 'تقارير المبيعات', self.reports_v81_dialog),
                       gate(None, 'كشف حساب الخزنة', self.statement_dialog) if role in ('manager', 'deputy') else None,
                       gate(None, 'سجل التدقيق', self.show_audit) if role == 'manager' else None]
        elif key == 'users':
            if role == 'manager':
                actions = [('＋  إدارة المستخدمين', self.add_user),
                           ('صلاحيات الموظفين', self.employee_permissions_page),
                           ('إعادة تعيين كلمة المرور', self.reset_password_dialog),
                           ('سجل التدقيق', self.show_audit)]
        elif key == 'settings':
            actions = [('↻  تحديث الأرصدة', self._refresh_from_hub),
                       ('نسخة احتياطية / استرداد', self.backup_database) if role in ('manager', 'deputy') else None,
                       ('فحص سلامة البيانات', self.database_health_dialog) if role == 'manager' else None,
                       gate('drafts', 'المسودات المعلّقة', self.offline_drafts_dialog) if role != 'manager' else None,
                       ('الإدارة المركزية', self.central_management_dialog) if role == 'deputy' else None,
                       ('الملف الشخصي', self.profile_dialog)]
        return [a for a in actions if a]

    def _module_open_all(self, key):
        """Target of the table's "عرض كل السجلات" link (None when the user lacks the permission)."""
        p = self._permitted
        if key == 'cash':
            return self.show_financial_form
        if key == 'sales' and p('invoices'):
            return self.invoices_dialog
        if key == 'stock' and p('inventory'):
            return self.inventory_dialog
        if key in ('raw', 'production', 'finished') and p('factory_history'):
            return lambda: self._factory_from(key, 'سجل الحركات')
        if key == 'transfers' and p('corrections'):
            return self.changes_dialog
        if key == 'users' and self.user['role'] == 'manager':
            return self.show_audit
        return None

    def _factory_from(self, module_key, tab):
        self._nav_override = module_key
        self._factory_module = module_key
        headings = {'إضافة منتج': {'raw': 'تسجيل حركة خامات', 'production': 'تسجيل حركة إنتاج',
                                   'finished': 'تسجيل حركة منتج تام'},
                    'الأرصدة': 'أرصدة المصنع', 'سجل الحركات': 'سجل حركات المصنع',
                    'الماكينات والأصناف': 'الماكينات والأصناف'}
        heading = headings.get(tab)
        self._card_heading = heading.get(module_key) if isinstance(heading, dict) else heading
        self.factory_dialog(tab)

    def _refresh_from_hub(self):
        self.refresh()
        hub = getattr(self, 'current_hub', None)
        if hub is not None:
            try:
                hub.reload()
            except Exception:
                self.current_hub = None

    def _select_nav(self, key):
        buttons = getattr(self, 'nav_buttons', {})
        target = buttons.get(key)
        if target is None:
            return
        for button in buttons.values():
            button.set_selected(button is target)
        self.active_sidebar_button = target

    def open_module(self, key):
        if key == 'dashboard':
            self.show_home()
        else:
            self.show_module(key)

    def show_module(self, key):
        """One Figma screen: header, 4 KPI cards, quick actions and a live data table."""
        import pmp_figma_ui
        module = next((m for m in MODULES if m[0] == key), None)
        actions = self._module_actions(key) if module else []
        if module is None or not actions:
            if module is not None:
                messagebox.showerror('الصلاحيات', 'ليس لديك صلاحية لفتح هذه الصفحة')
            self.show_home()
            return
        self._route('show_module', key)
        screen = self._embedded_screen(module[1], hub=True)
        scroll = SmoothScrollableFrame(screen, fg_color=M3['background'], corner_radius=0,
                                       scrollbar_button_color='#C5D2E3', scrollbar_button_hover_color='#9FB2CB')
        scroll.pack(fill='both', expand=True)
        p = self._permitted
        role = self.user['role']
        row_commands = {}
        if p('reports'):
            row_commands['reports_v81_dialog'] = self.reports_v81_dialog
        if role in ('manager', 'deputy'):
            row_commands['statement_dialog'] = self.statement_dialog
        row_commands['show_financial_form'] = self.show_financial_form
        if role == 'manager':
            row_commands['show_audit'] = self.show_audit
        ctx = {'db': DB, 'user': self.user, 'branch': self.branch_config, 'version': APP_VERSION,
               'commands': set(row_commands)}
        self.current_hub = pmp_figma_ui.Hub(scroll, key, ctx, module[1], module[3], actions,
                                            open_all=self._module_open_all(key), row_commands=row_commands)

    def _route(self, method, *args, **kwargs):
        """Remember the exact screen and parameters for one-step Back navigation."""
        self._pending_route = (method, args, kwargs)

    def _restore_route(self, route):
        if route is None:
            self.show_home()
            return
        method, args, kwargs = route
        self._restoring_route = True
        try:
            getattr(self, method)(*args, **kwargs)
        finally:
            self._restoring_route = False

    def _embedded_screen(self, title, hub=False, card=True):
        # Keep the dashboard packed permanently. Switching pack_forget/pack on
        # CTkScrollableFrame caused the blank main area after returning home.
        # An embedded page is a single overlay placed above the dashboard.
        if not self._restoring_route and self.current_embedded is not None:
            self.page_history.append(self._current_route)
        self._current_route = self._pending_route
        self._pending_route = None
        self.current_hub = None
        if self.current_embedded is not None:
            old = self.current_embedded
            self.current_embedded = None
            if old.winfo_exists():
                if old is self._dashboard_cache:
                    old.place_forget()
                else:
                    old.destroy()
        # Figma screen vocabulary. These are presentation-only aliases; the original
        # accounting, inventory and permission callbacks remain untouched.
        module_title, sub_title = SCREEN_NAMES.get(title, (title, None))
        override = getattr(self, '_nav_override', None)
        self._nav_override = None
        if override:
            module_title = next((m[1] for m in MODULES if m[0] == override), module_title)
        module = next((m for m in MODULES if m[1] == module_title), None)
        screen = EmbeddedPage(self.content_host, self.show_home)
        screen.place(relx=0, rely=0, relwidth=1, relheight=1)
        screen.lift()
        self.current_embedded = screen
        self._set_breadcrumb(module_title if (hub or not sub_title) else f'{module_title}  /  {sub_title}')
        if module:
            self._select_nav(module[0])
        if hub:
            return screen
        subtitle = (getattr(self, '_screen_subtitle', None) or SUBTITLES.get(title)
                    or (module[3] if module else 'إدارة العمليات والبيانات'))
        self._screen_subtitle = None
        head = ctk.CTkFrame(screen, fg_color='transparent')
        head.pack(fill='x', side='top', padx=26, pady=(8, 6))
        titles = ctk.CTkFrame(head, fg_color='transparent')
        titles.pack(side='right')
        ctk.CTkLabel(titles, text=sub_title or module_title, font=('Segoe UI', 30, 'bold'),
                     text_color=M3['ink'], anchor='e').pack(anchor='e')
        ctk.CTkLabel(titles, text=subtitle, font=('Segoe UI', 13), text_color=M3['muted'],
                     anchor='e').pack(anchor='e', pady=(2, 0))
        controls = ctk.CTkFrame(head, fg_color='transparent')
        controls.pack(side='left', anchor='n', pady=(10, 0))
        ctk.CTkButton(controls, text='⌂  الرئيسية', width=112, height=38, corner_radius=10,
                      font=('Segoe UI', 13, 'bold'), command=self.show_home,
                      fg_color=M3['primary'], hover_color=M3['primary_hover']).pack(side='left', padx=4)
        ctk.CTkButton(controls, text='↶  رجوع', width=98, height=38, corner_radius=10,
                      font=('Segoe UI', 13, 'bold'), command=self.go_back,
                      fg_color=M3['table_head'], hover_color='#DCE8FA',
                      text_color=M3['ink']).pack(side='left', padx=4)
        if not card:
            return screen
        # Figma internal screens: ONE white card with its own heading holds the whole form.
        host = EmbeddedCard(screen, self.show_home)
        host.pack(fill='both', expand=True, padx=26, pady=(8, 20))
        heading = getattr(self, '_card_heading', None)
        if heading is None:
            heading = sub_title or module_title
        self._card_heading = None
        if heading:
            ctk.CTkLabel(host, text=heading, font=('Segoe UI', 22, 'bold'), text_color=M3['ink'],
                         anchor='e').pack(fill='x', padx=34, pady=(24, 6))
        return host

    def _set_breadcrumb(self, title):
        """Header breadcrumb follows the open page (Figma: الرئيسية / الصفحة)."""
        try:
            self.breadcrumb_label.configure(text=(f'PMP  /  {title}' if title != 'لوحة التحكم' else 'الرئيسية  /  لوحة التحكم'))
        except (AttributeError, tk.TclError):
            pass

    def _close_embedded(self):
        if self.current_embedded is not None:
            screen = self.current_embedded
            self.current_embedded = None
            if screen.winfo_exists():
                if screen is self._dashboard_cache:
                    screen.place_forget()
                else:
                    screen.destroy()
        self.page.lift()

    def employee_permissions_page(self):
        from pmp_access import editor
        self._card_heading = 'إدارة الصلاحيات والأدوار'
        screen=self._embedded_screen('صلاحيات الموظفين')
        editor(screen, DB, self.user, embedded=True)

    def factory_dialog(self, initial_tab=None):
        from pmp_factory import open_factory
        module = getattr(self, '_factory_module', None)
        self._factory_module = None
        screen = self._embedded_screen('الإنتاج والتعبئة')
        open_factory(screen, self.user, DB, initial_tab=initial_tab, module=module)

    def show_home(self):
        """Manager dashboard; other users open financial entry."""
        if self.user['role'] != 'manager':
            self.show_financial_form()
            return
        self._close_embedded()
        self._set_breadcrumb('لوحة التحكم')
        self._select_nav('dashboard')
        self.current_hub = None
        self.page_history.clear()
        self._current_route = ('show_home', (), {})
        self._pending_route = None
        if self._dashboard_cache is not None and self._dashboard_cache.winfo_exists():
            self._dashboard_cache.place(relx=0, rely=0, relwidth=1, relheight=1)
            self._dashboard_cache.lift()
            self.current_embedded = self._dashboard_cache
            # The dashboard widgets are reused, but live financial figures are refreshed.
            self.refresh()
            reload_dashboard = getattr(self, '_dashboard_reload', None)
            if reload_dashboard:
                try:
                    reload_dashboard()   # KPI cards / chart / recent movements show current data
                except tk.TclError:
                    pass
        else:
            self._draw_dashboard()

    def _draw_dashboard(self):
        self._close_embedded()
        screen = SmoothScrollableFrame(self.content_host, fg_color=M3['background'], corner_radius=0,
                                       scrollbar_button_color='#C5D2E3', scrollbar_button_hover_color='#9FB2CB')
        screen.place(relx=0, rely=0, relwidth=1, relheight=1)
        screen.lift()
        self.current_embedded = screen
        self._dashboard_cache = screen
        # Figma page header: title block on the right, primary action on the left.
        head = ctk.CTkFrame(screen, fg_color='transparent')
        head.pack(fill='x', padx=26, pady=(4, 6))
        titles = ctk.CTkFrame(head, fg_color='transparent')
        titles.pack(side='right')
        ctk.CTkLabel(titles, text='نظرة عامة على الأعمال', font=('Segoe UI', 30, 'bold'),
                     text_color=M3['ink'], anchor='e').pack(anchor='e')
        ctk.CTkLabel(titles, text='متابعة الخزن، المبيعات، المخزون والحركات من مكان واحد',
                     font=('Segoe UI', 13), text_color=M3['muted'], anchor='e').pack(anchor='e', pady=(2, 0))
        ctk.CTkButton(head, text='＋  إضافة حركة جديدة', width=184, height=40, corner_radius=10,
                      font=('Segoe UI', 13, 'bold'), fg_color=M3['primary'],
                      hover_color=M3['primary_hover'],
                      command=self.show_financial_form).pack(side='left', pady=(12, 0))
        scroll = screen  # One unified scroll region for the whole dashboard.
        tools = ctk.CTkFrame(scroll, fg_color='transparent')
        tools.pack(fill='x', padx=26, pady=(6, 4))
        ctk.CTkLabel(tools, text='الفترة:', text_color=M3['ink'],
                     font=('Segoe UI', 12, 'bold')).pack(side='right', padx=7)
        period = ctk.CTkComboBox(tools, values=['اليوم', 'هذا الشهر', 'آخر 30 يوم', 'كل الفترات'],
                                  state='readonly', width=165)
        period.set('هذا الشهر')
        period.pack(side='right', padx=6)
        content = ctk.CTkFrame(scroll, fg_color='transparent')
        content.pack(fill='both', expand=True, padx=19)
        def reload(_choice=None):
            for child in content.winfo_children(): child.destroy()
            self._fill_dashboard(content, period.get())
        period.configure(command=reload)
        self._dashboard_reload = lambda: reload()
        ctk.CTkButton(tools, text='↻ تحديث البيانات', width=145, command=reload,
                      fg_color='#FFFFFF', hover_color='#E8EEF7', text_color=M3['ink'],
                      border_width=1, border_color=M3['outline'],
                      corner_radius=10, height=36).pack(side='right', padx=9)
        reload()

    def _quick_entry(self, direction):
        """Open the entry form with the movement type already chosen (وارد / منصرف)."""
        self.show_financial_form()
        try:
            self.fields['direction'].set(direction)
        except (AttributeError, KeyError, tk.TclError):
            pass

    def _dashboard_action_row(self, parent, text, color, callback):
        """Figma quick-action row: soft pill, coloured indicator on the right, whole row clickable."""
        row = ctk.CTkFrame(parent, fg_color='#F5F8FC', corner_radius=9, height=38)
        row.pack(fill='x', padx=22, pady=3)
        row.pack_propagate(False)
        bar = ctk.CTkFrame(row, width=5, height=17, corner_radius=3, fg_color=color)
        bar.pack(side='right', padx=(0, 14), pady=10)
        label = ctk.CTkLabel(row, text=text, font=('Segoe UI', 13, 'bold'),
                             text_color=M3['ink'], anchor='e')
        label.pack(side='right', fill='x', expand=True, padx=(8, 10))
        def enter(_event):
            row.configure(fg_color='#E9F0FA')
        def leave(_event):
            try:
                px, py = row.winfo_pointerxy()
                if 0 <= px-row.winfo_rootx() < row.winfo_width() and 0 <= py-row.winfo_rooty() < row.winfo_height():
                    return
            except tk.TclError:
                return
            row.configure(fg_color='#F5F8FC')
        for widget in (row, bar, label):
            widget.configure(cursor='hand2')
            widget.bind('<Enter>', enter, add='+')
            widget.bind('<Leave>', leave, add='+')
            widget.bind('<Button-1>', lambda _event: callback(), add='+')

    def _fill_dashboard(self, parent, period):
        from datetime import timedelta
        today = date.today()
        start = {'اليوم': today.isoformat(),
                 'هذا الشهر': today.replace(day=1).isoformat(),
                 'آخر 30 يوم': (today-timedelta(days=29)).isoformat(),
                 'كل الفترات': '0001-01-01'}[period]
        end = today.isoformat()
        try:
            with connection() as con:
                balances = con.execute("""SELECT cashbox,currency,
                    SUM(CASE WHEN direction='وارد' THEN amount ELSE -amount END)
                    FROM movements WHERE tx_date<=? GROUP BY cashbox,currency
                    ORDER BY cashbox,currency""", (end,)).fetchall()
                cash = con.execute("""SELECT currency,direction,COALESCE(SUM(amount),0)
                    FROM movements WHERE tx_date BETWEEN ? AND ?
                    GROUP BY currency,direction ORDER BY currency""", (start,end)).fetchall()
                sales = con.execute("""SELECT currency,COUNT(*),COALESCE(SUM(CAST(total AS REAL)),0) FROM invoices
                    WHERE status='active' AND invoice_date BETWEEN ? AND ? GROUP BY currency""",(start,end)).fetchall()
                debts = con.execute("""SELECT i.currency,i.total,
                    COALESCE((SELECT SUM(CAST(p.amount AS REAL)) FROM invoice_payments p
                      WHERE p.invoice_id=i.id AND p.status='active'),0)
                    FROM invoices i WHERE i.status='active'""").fetchall()
                stocks = con.execute("""SELECT w.name,p.name,p.package_weight,p.unit,p.minimum_stock,
                    COALESCE(SUM(CAST(s.quantity AS REAL)),0)
                    FROM products p CROSS JOIN warehouses w
                    LEFT JOIN stock_moves s ON s.product_id=p.id AND s.warehouse_id=w.id
                    WHERE p.active=1 AND w.active=1
                    GROUP BY w.id,p.id""").fetchall()
                trend = con.execute("""SELECT substr(tx_date,1,7),currency,direction,SUM(amount)
                    FROM movements WHERE tx_date BETWEEN ? AND ?
                    GROUP BY substr(tx_date,1,7),currency,direction ORDER BY 1""",(start,end)).fetchall()
                sales_trend = con.execute("""SELECT substr(invoice_date,1,7),currency,SUM(CAST(total AS REAL))
                    FROM invoices WHERE status='active' AND invoice_date BETWEEN ? AND ?
                    GROUP BY substr(invoice_date,1,7),currency ORDER BY 1""",(start,end)).fetchall()
                today_count = con.execute("SELECT COUNT(*) FROM movements WHERE tx_date=?",
                                          (today.isoformat(),)).fetchone()[0]
                week_rows = con.execute("""SELECT invoice_date,currency,COALESCE(SUM(CAST(total AS REAL)),0)
                    FROM invoices WHERE status='active' AND invoice_date BETWEEN ? AND ?
                    GROUP BY invoice_date,currency""",
                    ((today-timedelta(days=6)).isoformat(),end)).fetchall()
                recent = con.execute("""SELECT m.id,COALESCE(m.reference,''),m.tx_date,m.cashbox,m.currency,
                    m.direction,m.amount,
                    EXISTS(SELECT 1 FROM change_requests c WHERE c.movement_id=m.id AND c.status='pending')
                    FROM movements m ORDER BY m.tx_date DESC,m.id DESC LIMIT 8""").fetchall()
        except Exception as exc:
            ctk.CTkLabel(parent, text=f'تعذر تحميل الملخص: {exc}', text_color='#B42335').pack(pady=20)
            return
        from collections import defaultdict
        receipts=defaultdict(float); expenses=defaultdict(float); revenue=defaultdict(float)
        counts=defaultdict(int); owing=defaultdict(float)
        for currency,direction,amount in cash:
            (receipts if direction=='وارد' else expenses)[currency]+=float(amount)
        for currency,count,amount in sales:
            revenue[currency]+=float(amount); counts[currency]+=int(count)
        for currency,total,paid in debts:
            owing[currency]+=max(0,float(total)-float(paid))
        def section(title):
            panel=ctk.CTkFrame(parent, fg_color='#FFFFFF', corner_radius=14,
                               border_width=1,border_color=M3['outline'])
            panel.pack(fill='x',pady=9)
            ctk.CTkLabel(panel,text=title,font=('Segoe UI',15,'bold'),text_color='#10243E',
                         anchor='e').pack(fill='x',padx=20,pady=(16,10))
            return panel
        def cards(title, entries):
            # Currency-safe KPI grid: 2 tiles per row, strong hierarchy, no tiny glyphs.
            heading=ctk.CTkFrame(parent,fg_color='transparent')
            heading.pack(fill='x',pady=(18,8),padx=4)
            ctk.CTkLabel(heading,text=title,font=('Segoe UI',19,'bold'),
                         text_color='#10243E',anchor='e').pack(side='right',padx=9)
            grid=ctk.CTkFrame(parent,fg_color='transparent')
            grid.pack(fill='x',pady=(0,11))
            columns=2
            for col in range(columns):grid.grid_columnconfigure(col,weight=1,uniform='v102_kpi')
            palette=[('#1764D8','#EAF1FD','↙'),('#159D76','#E6F6F1','↓'),
                     ('#C79535','#FCF3E1','↗'),('#805AD5','#F1EBFA','▤')]
            for i,(label,value) in enumerate(entries):
                accent,soft,glyph=palette[i%len(palette)]
                card=ctk.CTkFrame(grid,fg_color='#FFFFFF',corner_radius=14,
                                  border_width=1,border_color=M3['outline'],height=134)
                card.grid(row=i//columns,column=(columns-1)-(i%columns),
                          sticky='nsew',padx=6,pady=6)
                card.grid_propagate(False)
                head=ctk.CTkFrame(card,fg_color='transparent')
                head.pack(fill='x',padx=18,pady=(17,6))
                ctk.CTkLabel(head,text=label,font=('Segoe UI',13,'bold'),
                             text_color='#536B85',anchor='e').pack(side='right',fill='x',expand=True)
                ctk.CTkLabel(head,text=glyph,font=('Segoe UI Symbol',23,'bold'),
                             text_color=accent,fg_color=soft,corner_radius=13,
                             width=49,height=49).pack(side='left')
                ctk.CTkLabel(card,text=value,font=('Segoe UI',23,'bold'),
                             text_color='#10243E',anchor='e',justify='right').pack(fill='x',padx=19,pady=(0,10))
        def by_currency(data):
            return '  |  '.join(f'{cur}: {format_money(value)}' for cur,value in sorted(data.items())) or 'لا توجد بيانات'
        # Each monetary KPI is a separate currency tile; never combine SDG and EGP.
        # Include both branch currencies even if a currency has no movements in this period.
        currencies = sorted(set(receipts) | set(expenses) | set(revenue) | set(owing) | {'SDG', 'EGP'})
        def currency_tiles(label, totals):
            return [(f'{label} — {cur}', f'{format_money(totals.get(cur, 0))} {cur}')
                    for cur in currencies]
        # ---- V11.8 Figma dashboard: KPI cards, weekly sales chart, quick actions, recent movements.
        # Every monetary figure is shown per currency; currencies are never added together.
        BLUE, GREEN, GOLD, PURPLE = M3['primary'], M3['green'], M3['gold'], M3['purple']
        balance_totals = defaultdict(float)
        for _box, cur, amount in balances:
            balance_totals[cur] += float(amount)
        outstanding = sum(1 for _cur, total, paid in debts if float(total)-float(paid) > 0.005)
        kpi_grid = ctk.CTkFrame(parent, fg_color='transparent')
        kpi_grid.pack(fill='x', pady=(2, 8))
        for col in range(4):
            kpi_grid.grid_columnconfigure(col, weight=1, uniform='fig_kpi')
        def kpi(col, label, accent, rows, note, note_color):
            card = ctk.CTkFrame(kpi_grid, fg_color='#FFFFFF', corner_radius=14,
                                border_width=1, border_color=M3['outline'])
            card.grid(row=0, column=3-col, sticky='nsew', padx=7, pady=4)
            ctk.CTkFrame(card, width=5, height=48, corner_radius=3, fg_color=accent).place(
                relx=1.0, x=-22, y=16, anchor='ne')
            ctk.CTkLabel(card, text=label, font=('Segoe UI', 13), text_color=M3['muted'],
                         anchor='e').pack(fill='x', padx=(14, 40), pady=(15, 2))
            for value, unit in rows:
                line = ctk.CTkFrame(card, fg_color='transparent')
                line.pack(fill='x', padx=(14, 40), pady=(0, 2))
                ctk.CTkLabel(line, text=value, font=('Segoe UI', 21, 'bold'),
                             text_color=M3['ink'], anchor='e').pack(side='right')
                ctk.CTkLabel(line, text=unit, font=('Segoe UI', 11),
                             text_color=M3['muted']).pack(side='left', pady=(8, 0))
            ctk.CTkLabel(card, text=note, font=('Segoe UI', 11, 'bold'), text_color=note_color,
                         anchor='e').pack(fill='x', padx=(14, 40), pady=(2, 14))
        kpi(0, 'إجمالي أرصدة الخزن', BLUE,
            [(format_money(balance_totals.get(c, 0)), c) for c in currencies], 'حتى اليوم', BLUE)
        kpi(1, f'مبيعات {period}', GREEN,
            [(format_money(revenue.get(c, 0)), c) for c in currencies],
            f'{sum(counts.values())} فاتورة في الفترة', GREEN)
        kpi(2, 'الفواتير المستحقة', GOLD,
            [(str(outstanding), 'فاتورة')] + [(format_money(owing[c]), c) for c in currencies if owing.get(c, 0) > 0.005],
            'تحتاج مراجعة' if outstanding else 'لا توجد مستحقات', '#BB8624' if outstanding else GREEN)
        kpi(3, 'حركات اليوم', PURPLE, [(str(today_count), 'حركة')],
            'آخر تحديث ' + datetime.now().strftime('%H:%M'), PURPLE)

        mid = ctk.CTkFrame(parent, fg_color='transparent')
        mid.pack(fill='x', pady=(4, 8))
        mid.grid_columnconfigure(0, weight=2, uniform='fig_mid')
        mid.grid_columnconfigure(1, weight=1, uniform='fig_mid')
        mid.grid_rowconfigure(0, weight=1)
        # Sales chart (left column in RTL): last 7 days, one currency at a time.
        chart_panel = ctk.CTkFrame(mid, fg_color='#FFFFFF', corner_radius=14,
                                   border_width=1, border_color=M3['outline'])
        chart_panel.grid(row=0, column=0, sticky='nsew', padx=(7, 7))
        chart_head = ctk.CTkFrame(chart_panel, fg_color='transparent')
        chart_head.pack(fill='x', padx=22, pady=(16, 0))
        chart_titles = ctk.CTkFrame(chart_head, fg_color='transparent')
        chart_titles.pack(side='right')
        ctk.CTkLabel(chart_titles, text='حركة المبيعات', font=('Segoe UI', 18, 'bold'),
                     text_color=M3['ink'], anchor='e').pack(anchor='e')
        ctk.CTkLabel(chart_titles, text='ملخص المبيعات خلال آخر سبعة أيام  •  كل عملة على حدة',
                     font=('Segoe UI', 11), text_color=M3['muted'], anchor='e').pack(anchor='e')
        week_days = [today-timedelta(days=6-i) for i in range(7)]
        week_totals = defaultdict(lambda: defaultdict(float))
        for day, cur, total in week_rows:
            week_totals[cur][str(day)] += float(total)
        chart_currencies = list(currencies)
        default_currency = next((c for c in chart_currencies if any(week_totals[c].values())),
                                chart_currencies[0])
        weekday_names = ['الإثنين', 'الثلاثاء', 'الأربعاء', 'الخميس', 'الجمعة', 'السبت', 'الأحد']
        chart = tk.Canvas(chart_panel, height=230, bg='#FFFFFF', highlightthickness=0)
        def draw_week(_event=None):
            chart.delete('all')
            width = max(320, chart.winfo_width())
            height = 230
            cur = currency_switch.get()
            values = [week_totals[cur].get(d.isoformat(), 0.0) for d in week_days]
            peak = max(values) or 1.0
            slot = (width-40) / len(week_days)
            bar_width = min(52, slot*0.58)
            base, top = height-34, 30
            for i, (day, value) in enumerate(zip(week_days, values)):
                cx = 20 + slot*(i+0.5)
                bar_height = max(5, (base-top)*value/peak) if value > 0 else 5
                _round_rect(chart, cx-bar_width/2, base-bar_height, cx+bar_width/2, base, 8,
                            fill=BLUE if i == len(week_days)-1 else M3['chart_soft'], outline='')
                if value > 0:
                    chart.create_text(cx, base-bar_height-11, text=_compact_amount(value),
                                      fill=M3['muted'], font=('Segoe UI', 9, 'bold'))
                chart.create_text(cx, height-14, text=weekday_names[day.weekday()],
                                  fill=M3['muted'], font=('Segoe UI', 10))
        currency_switch = ctk.CTkSegmentedButton(
            chart_head, values=chart_currencies, command=lambda _v: draw_week(),
            height=30, font=('Segoe UI', 11, 'bold'), fg_color='#ECF2FA',
            selected_color=M3['chart_soft'], selected_hover_color='#9EC0EE',
            unselected_color='#ECF2FA', unselected_hover_color='#DCE7F6', text_color=M3['ink'])
        currency_switch.set(default_currency)
        currency_switch.pack(side='left', pady=(6, 0))
        chart.pack(fill='both', expand=True, padx=18, pady=(8, 14))
        chart.bind('<Configure>', draw_week)
        # Quick actions (right column in RTL). Keeps every shortcut the old tiles offered.
        quick_panel = ctk.CTkFrame(mid, fg_color='#FFFFFF', corner_radius=14,
                                   border_width=1, border_color=M3['outline'])
        quick_panel.grid(row=0, column=1, sticky='nsew', padx=(7, 7))
        ctk.CTkLabel(quick_panel, text='إجراءات سريعة', font=('Segoe UI', 18, 'bold'),
                     text_color=M3['ink'], anchor='e').pack(fill='x', padx=22, pady=(16, 8))
        for label, color, callback in [
                ('إضافة إيراد', GREEN, lambda: self._quick_entry('وارد')),
                ('تسجيل مصروف', BLUE, lambda: self._quick_entry('منصرف')),
                ('تحويل بين الخزن', PURPLE, self.transfer_dialog),
                ('إصدار فاتورة', M3['gold_dark'], self.new_invoice_dialog),
                ('كشف حساب الخزنة', BLUE, self.statement_dialog),
                ('توريد مخزون', GREEN, self.inventory_dialog),
                ('المسودات', PURPLE, self.offline_drafts_dialog),
                ('الإدارة المركزية', M3['gold_dark'], self.central_management_dialog)]:
            self._dashboard_action_row(quick_panel, label, color, callback)
        ctk.CTkFrame(quick_panel, fg_color='transparent', height=10).pack()

        # Recent movements table (alternating rows, status pill as coloured text).
        recent_panel = ctk.CTkFrame(parent, fg_color='#FFFFFF', corner_radius=14,
                                    border_width=1, border_color=M3['outline'])
        recent_panel.pack(fill='x', padx=7, pady=(4, 10))
        recent_head = ctk.CTkFrame(recent_panel, fg_color='transparent')
        recent_head.pack(fill='x', padx=22, pady=(16, 8))
        ctk.CTkLabel(recent_head, text='آخر الحركات المالية', font=('Segoe UI', 18, 'bold'),
                     text_color=M3['ink']).pack(side='right')
        ctk.CTkButton(recent_head, text='عرض جميع الحركات  ←', width=150, height=28,
                      fg_color='transparent', hover_color='#EAF1FB', text_color=BLUE,
                      font=('Segoe UI', 12, 'bold'),
                      command=self.show_financial_form).pack(side='left')
        table = ctk.CTkFrame(recent_panel, fg_color='transparent')
        table.pack(fill='x', padx=18, pady=(0, 16))
        table.grid_columnconfigure(0, weight=1)
        def table_row(index, cells, background, weight, colors=None):
            rowf = ctk.CTkFrame(table, fg_color=background, corner_radius=8, height=34)
            rowf.grid(row=index, column=0, sticky='ew', pady=1)
            rowf.grid_propagate(False)
            for c in range(6):
                rowf.grid_columnconfigure(c, weight=1, uniform='fig_tbl')
            for i, text in enumerate(cells):   # first cell on the right (RTL)
                ctk.CTkLabel(rowf, text=text, font=('Segoe UI', 12, weight),
                             text_color=(colors[i] if colors else M3['ink']),
                             anchor='e').grid(row=0, column=5-i, sticky='ew', padx=12, pady=4)
        table_row(0, ['المرجع', 'التاريخ', 'الخزنة', 'نوع الحركة', 'المبلغ', 'الحالة'],
                  M3['table_head'], 'bold')
        if not recent:
            ctk.CTkLabel(table, text='لا توجد حركات بعد', text_color=M3['muted']).grid(row=1, column=0, pady=14)
        for i, (_mid, reference, tx_date, box, cur, direction, amount, pending) in enumerate(recent):
            table_row(i+1,
                      [reference or f'#{_mid}', str(tx_date), str(box), str(direction),
                       f'{format_money(amount)} {cur}', 'قيد المراجعة' if pending else 'مكتمل'],
                      M3['table_alt'] if i % 2 else '#FFFFFF', 'normal',
                      [M3['ink'], M3['ink'], M3['ink'],
                       GREEN if direction == 'وارد' else '#B42335', M3['ink'],
                       '#BB8624' if pending else GREEN])

        cards('المؤشرات الرئيسية | الفترة المختارة',
              currency_tiles('المقبوضات', receipts) +
              currency_tiles('المصروفات', expenses) +
              [('عدد الفواتير', str(sum(counts.values())))] +
              currency_tiles('قيمة المبيعات', revenue))
        cards('متابعة العملاء', currency_tiles('المديونيات الحالية', owing))
        # V9.8.4: Material 3 responsive cashbox and low-stock card grids.
        panel=section('أرصدة الخزن حسب العملة (حتى اليوم)')
        if balances:
            cash_grid=ctk.CTkFrame(panel,fg_color='transparent')
            cash_grid.pack(fill='x',padx=12,pady=(0,15))
            columns=2
            for col in range(columns):cash_grid.grid_columnconfigure(col,weight=1,uniform='cash')
            for i,(box,cur,amount) in enumerate(balances):
                tile=ctk.CTkFrame(cash_grid,fg_color=M3['surface_dim'],corner_radius=17,
                                  border_width=1,border_color=M3['outline'])
                tile.grid(row=i//columns,column=(columns-1)-(i%columns),sticky='nsew',padx=5,pady=5)
                head=ctk.CTkFrame(tile,fg_color='transparent')
                head.pack(fill='x',padx=14,pady=(12,2))
                ctk.CTkLabel(head,text=str(cur),font=('Segoe UI',11,'bold'),text_color=M3['primary'],
                             fg_color=M3['surface'],corner_radius=10,width=55,height=26).pack(side='left')
                ctk.CTkLabel(head,text=str(box),font=('Segoe UI',12,'bold'),text_color=M3['ink'],
                             anchor='e').pack(side='right',fill='x',expand=True)
                ctk.CTkLabel(tile,text=format_money(amount),font=('Segoe UI',19,'bold'),
                             text_color=M3['nav'],anchor='e').pack(fill='x',padx=15,pady=(4,15))
        else:
            ctk.CTkLabel(panel,text='لا توجد أرصدة',text_color=M3['muted']).pack(pady=12)
        low=[(w,n,weight,unit,qty) for w,n,weight,unit,minimum,qty in stocks if float(qty)<=float(minimum)]
        cards('ملخص المخزون', [('أرصدة الأصناف بالمخازن',str(len(stocks))),
                               ('أصناف عند أو دون الحد الأدنى',str(len(low)))])
        if low:
            panel=section(f'تنبيهات المخزون المنخفض  •  {len(low)} أصناف')
            stock_grid=ctk.CTkFrame(panel,fg_color='transparent')
            stock_grid.pack(fill='x',padx=12,pady=(0,15))
            columns=3
            for col in range(columns):stock_grid.grid_columnconfigure(col,weight=1,uniform='stock')
            for i,(w,n,weight,unit,qty) in enumerate(low):
                tile=ctk.CTkFrame(stock_grid,fg_color=M3['surface_dim'],corner_radius=20,
                                  border_width=1,border_color=M3['outline'])
                tile.grid(row=i//columns,column=(columns-1)-(i%columns),sticky='nsew',padx=5,pady=5)
                ctk.CTkLabel(tile,text=str(n),font=('Segoe UI',13,'bold'),text_color=M3['ink']).pack(padx=8,pady=(12,3))
                ctk.CTkLabel(tile,text=f'{float(qty):g}',font=('Segoe UI',23,'bold'),
                             text_color='#B45309' if float(qty)>0 else '#B42335').pack(padx=8,pady=2)
                ctk.CTkLabel(tile,text=f'الوزن: {weight or "—"}',font=('Segoe UI',11),
                             text_color=M3['muted']).pack(padx=8,pady=(2,12))
        # Aggregate all chart values separately by currency; never combine distinct currencies.
        panel=section('اتجاه المقبوضات والمصروفات والمبيعات حسب الشهر والعملة')
        chart_rows=defaultdict(float)
        for month,cur,direction,amount in trend:
            chart_rows[(month,cur,'وارد' if direction=='وارد' else 'منصرف')]+=float(amount)
        for month,cur,amount in sales_trend:
            chart_rows[(month,cur,'مبيعات')]+=float(amount)
        if not chart_rows:
            ctk.CTkLabel(panel,text='لا توجد حركة في الفترة المختارة',
                         text_color='#64748B').pack(pady=20)
        else:
            # Draw a real time-series chart for each currency; never sum currencies.
            from collections import defaultdict as _dd
            currency_series=_dd(lambda:_dd(lambda:_dd(float)))
            for (month,cur,kind),amount in chart_rows.items():
                currency_series[cur][kind][month]+=float(amount)
            for cur,kind_data in sorted(currency_series.items()):
                months=sorted({m for vals in kind_data.values() for m in vals})
                sub=ctk.CTkFrame(panel,fg_color='#F8FAFC',corner_radius=12)
                sub.pack(fill='x',padx=17,pady=(2,12))
                ctk.CTkLabel(sub,text=f'العملة: {cur}   |   اتجاه الحركة الشهري',
                             font=('Segoe UI',11,'bold'),text_color='#10243E',
                             anchor='e').pack(fill='x',padx=14,pady=(10,3))
                canvas=tk.Canvas(sub,height=174,bg='#F8FAFC',highlightthickness=0)
                canvas.pack(fill='x',padx=12,pady=(0,5))
                def draw_chart(event,cv=canvas,ms=months,series=kind_data):
                    cv.delete('all')
                    w=max(260,event.width);h=174; left=24;right=w-20;top=13;bottom=143
                    for n in range(5):
                        y=top+(bottom-top)*n/4
                        cv.create_line(left,y,right,y,fill='#E2E8F0',dash=(3,4))
                    max_value=max((v for d in series.values() for v in d.values()),default=0) or 1
                    colors={'وارد':'#1764D8','منصرف':'#F97316','مبيعات':'#10B981'}
                    for kind,values in series.items():
                        points=[]
                        for i,month in enumerate(ms):
                            x=left+(right-left)*(i/max(1,len(ms)-1))
                            y=bottom-(bottom-top)*(values.get(month,0)/max_value)
                            points.extend((x,y))
                        if len(points)>=4:cv.create_line(*points,fill=colors.get(kind,'#64748B'),width=3,smooth=False)
                        for j in range(0,len(points),2):
                            x,y=points[j:j+2];cv.create_oval(x-3,y-3,x+3,y+3,fill=colors.get(kind,'#64748B'),outline='white')
                    step=max(1,(len(ms)+5)//6)
                    for i in range(0,len(ms),step):
                        x=left+(right-left)*(i/max(1,len(ms)-1))
                        cv.create_text(x,159,text=ms[i],fill='#64748B',font=('Segoe UI',9))
                canvas.bind('<Configure>',draw_chart)
                legend=ctk.CTkFrame(sub,fg_color='transparent');legend.pack(pady=(0,10))
                for kind,color in [('وارد','#1764D8'),('منصرف','#F97316'),('مبيعات','#10B981')]:
                    if kind in kind_data:
                        ctk.CTkLabel(legend,text='● '+kind,text_color=color,
                                     font=('Segoe UI',11,'bold')).pack(side='right',padx=10)

    def go_back(self):
        if self.page_history:
            route = self.page_history.pop()
            self._restore_route(route)
        else:
            self.show_home()

    def show_financial_form(self):
        """Show the existing income/expense entry form, always from its top."""
        self._close_embedded()
        self._set_breadcrumb('تسجيل حركة مالية')
        self.current_hub = None
        self._select_nav('cash' if self.user['role'] == 'manager' and 'cash' in self.nav_buttons else 'dashboard')
        self.page_history.clear()
        self._current_route = None
        # The financial form and movement history share a scrollable page.
        # Returning from the dashboard used to preserve the old scroll offset,
        # so users saw only the history table and thought the form was removed.
        def scroll_to_entry():
            try:
                if self.page.winfo_exists():
                    self.page._parent_canvas.yview_moveto(0.0)
            except (AttributeError, tk.TclError):
                pass
        scroll_to_entry()
        self.root.after_idle(scroll_to_entry)
        self.root.after(120, scroll_to_entry)

    def closing_dialog(self):
        popup=self._embedded_screen('closing_dialog')
        popup.title('PMP | الإقفال اليومي والجرد')
        popup.geometry('980x700')
        popup.minsize(760,550)
        popup.transient(self.root)
        popup.grab_set()
        import pmp_widgets as W
        outer=ctk.CTkScrollableFrame(popup,fg_color='transparent')
        outer.pack(fill='both',expand=True,padx=26,pady=(0,12))
        form=ctk.CTkFrame(outer,fg_color='white',corner_radius=16)
        form.pack(fill='x',pady=8)
        ctk.CTkLabel(form,text='الإقفال اليومي والجرد',font=('Segoe UI',22,'bold'),text_color=M3['ink'],
                     anchor='e').pack(fill='x',padx=40,pady=(22,2))
        ctk.CTkLabel(form,text='الإقفال لكل خزنة وعملة وتاريخ. لا يتم تعديل الرصيد الدفتري تلقائياً.',
                     font=('Segoe UI',12),text_color=M3['muted'],anchor='e').pack(fill='x',padx=40,pady=(0,4))
        grid=W.FormGrid(form,columns=3)
        grid.pack(fill='x',padx=26,pady=(0,4))
        def combo(values,default):
            def build(parent):
                w=RoundedCombo(parent,values=list(values),state='readonly',width=17)
                w.configure(height=42);w.set(default);return w
            return build
        box_w=grid.add('الخزنة',combo(CASHBOXES,CASHBOXES[0]))
        cur_w=grid.add('العملة',combo(CURRENCIES,CURRENCIES[0]))
        date_w=grid.add('تاريخ الإقفال (YYYY-MM-DD)',lambda c:W.field_entry(c,''))
        date_w.insert(0,date.today().isoformat())
        def readout(parent):
            return ctk.CTkLabel(parent,text='—',height=42,corner_radius=10,fg_color='#F5F8FC',anchor='e',
                                font=('Segoe UI',13,'bold'),text_color=M3['ink'],justify='right')
        balance_label=grid.add('الرصيد المتوقع (الدفتري)',readout)
        counted=grid.add('الرصيد الفعلي',lambda c:W.field_entry(c,'0'))
        difference_label=grid.add('الفرق',readout)
        notes=grid.add('ملاحظات الجرد',lambda c:W.field_entry(c,'ملاحظات'),span=3)
        def preview(*_):
            try:
                chosen_date=date.fromisoformat(date_w.get()).isoformat()
                with connection() as con:
                    entries=con.execute('SELECT direction,amount FROM movements WHERE tx_date<=? AND cashbox=? AND currency=?',
                        (chosen_date,box_w.get(),cur_w.get())).fetchall()
                book=Decimal(sum((1 if d=='وارد' else -1)*_cents(a) for d,a in entries))/100
                balance_label.configure(text=f'{format_money(book)} {cur_w.get()}')
                try:
                    actual=Decimal(counted.get().strip())
                    if not actual.is_finite():raise ValueError()
                    diff=actual-book
                    difference_label.configure(text=f'{format_money(diff)} {cur_w.get()}',
                                               text_color='#08754B' if diff==0 else '#B42335')
                except (InvalidOperation,ValueError):difference_label.configure(text='—',text_color=M3['ink'])
            except (ValueError,sqlite3.Error):balance_label.configure(text='راجع تاريخ الجرد')
        for w in (date_w,counted):w.bind('<KeyRelease>',preview,add='+')
        for w in (box_w,cur_w):w.configure(command=lambda _v:preview())
        actions=ctk.CTkFrame(form,fg_color='transparent');actions.pack(pady=(8,20))
        def approve():
            if self.user['role'] not in ('manager','deputy'):
                return messagebox.showerror('الصلاحيات','اعتماد الإقفال للمدير ونائب المدير فقط',parent=popup)
            if not messagebox.askyesno('تأكيد','هل تريد اعتماد إقفال هذا اليوم؟ ستُمنع الحركات بتاريخ اليوم المقفل.',parent=popup):return
            try:
                book,diff=close_day(date_w.get().strip(),box_w.get(),cur_w.get(),counted.get().strip(),notes.get(),
                                    self.user['username'],self.user['role'])
                reload();messagebox.showinfo('تم الإقفال',f'الرصيد الدفتري {format_money(book)} / الفرق {format_money(diff)}',parent=popup)
            except (ValueError,PermissionError,sqlite3.Error,InvalidOperation) as exc:
                messagebox.showerror('تعذر الإقفال',str(exc),parent=popup)
        if self.user['role'] in ('manager','deputy'):
            W.primary_button(actions,'اعتماد الإقفال',approve,width=260).pack(side='right',padx=6)
        W.primary_button(actions,'معاينة الرصيد',preview,width=200,tone='soft').pack(side='right',padx=6)
        ctk.CTkLabel(outer,text='سجل الإقفالات وإعادة الفتح',font=('Segoe UI',15,'bold'),text_color=M3['ink']).pack(pady=9)
        frame=ctk.CTkFrame(outer);frame.pack(fill='both',expand=True,padx=8,pady=5)
        cols=('id','date','box','currency','book','actual','difference','status','approved')
        tree=ttk.Treeview(frame,columns=cols,show='headings',height=10)
        for key,title,width in [('id','رقم',55),('date','التاريخ',110),('box','الخزنة',140),('currency','العملة',65),
            ('book','دفتري',110),('actual','فعلي',110),('difference','الفرق',110),('status','الحالة',95),('approved','اعتمد بواسطة',110)]:
            tree.heading(key,text=title);tree.column(key,width=width,anchor='center')
        tree.pack(side='left',fill='both',expand=True)
        sc=ttk.Scrollbar(frame,orient='vertical',command=tree.yview);sc.pack(side='right',fill='y')
        tree.configure(yscrollcommand=sc.set)
        def reload():
            with connection() as con:
                rows=con.execute('SELECT id,close_date,cashbox,currency,book_balance,counted_balance,difference,status,approved_by FROM daily_closings ORDER BY id DESC LIMIT 500').fetchall()
            for i in tree.get_children():tree.delete(i)
            for r in rows:
                values=list(r)
                for j in (4,5,6):values[j]=format_money(Decimal(values[j]))
                values[7]='مقفل' if r[7]=='closed' else 'أعيد فتحه'
                tree.insert('','end',values=values)
        def reopen():
            if self.user['role'] not in ('manager','deputy'):
                return messagebox.showerror('الصلاحيات','إعادة الفتح للمدير ونائب المدير فقط',parent=popup)
            selected=tree.selection()
            if not selected:return messagebox.showwarning('تنبيه','حدد إقفالاً من الجدول',parent=popup)
            from tkinter import simpledialog
            reason=simpledialog.askstring('إعادة فتح اليوم','اكتب سبب إعادة الفتح (إجباري):',parent=popup)
            if reason is None:return
            try:
                reopen_day(int(tree.item(selected[0],'values')[0]),reason,self.user['username'],self.user['role'])
                reload();messagebox.showinfo('تم','تمت إعادة فتح اليوم وتسجيل السبب',parent=popup)
            except (ValueError,PermissionError,sqlite3.Error) as exc:
                messagebox.showerror('تعذر إعادة الفتح',str(exc),parent=popup)
        if self.user['role'] in ('manager','deputy'):
            RoundedButton(outer,text='إعادة فتح الإقفال المحدد',command=reopen).pack(pady=10)
        reload();preview()

    def changes_dialog(self):
        window=self._embedded_screen('changes_dialog')
        window.title('PMP | طلبات التصحيح والإلغاء')
        window.geometry('1050x700')
        window.transient(self.root)
        outer=ctk.CTkScrollableFrame(window,fg_color='transparent')
        outer.pack(fill='both',expand=True,padx=12,pady=12)
        ctk.CTkLabel(outer,text='حدد حركة من جدول الصفحة الرئيسية أو اكتب رقمها. التحويلات يمكن إلغاؤها بالكامل فقط.',text_color='#36577B').pack(pady=5)
        entryrow=ctk.CTkFrame(outer,fg_color='transparent');entryrow.pack(fill='x',pady=8)
        ctk.CTkLabel(entryrow,text='رقم الحركة').pack(side='right',padx=6)
        movement_entry=RoundedEntry(entryrow,width=12);movement_entry.pack(side='right',padx=6)
        selected=self.tree.selection()
        if selected:
            movement_entry.insert(0,str(self.tree.item(selected[0],'values')[0]))
        ctk.CTkLabel(entryrow,text='نوع الطلب').pack(side='right',padx=6)
        request_kind=RoundedCombo(entryrow,values=['تصحيح','إلغاء'],state='readonly',width=14)
        request_kind.set('تصحيح');request_kind.pack(side='right',padx=6)
        ctk.CTkLabel(outer,text='عند التصحيح: أدخل البيانات الجديدة كاملة. رقم المستند الأصلي محفوظ ولا يعاد استخدامه.',text_color='#36577B').pack(anchor='e')
        fields={}
        labels=[('tx_date','التاريخ'),('cashbox','الخزنة'),('currency','العملة'),('direction','النوع'),('amount','المبلغ'),('description','البيان')]
        grid=ctk.CTkFrame(outer,fg_color='white',corner_radius=12);grid.pack(fill='x',pady=8)
        for ix,(key,label) in enumerate(labels):
            cell=ctk.CTkFrame(grid,fg_color='transparent');cell.grid(row=ix//3,column=2-ix%3,sticky='ew',padx=9,pady=8)
            grid.grid_columnconfigure(2-ix%3,weight=1)
            ctk.CTkLabel(cell,text=label).pack(anchor='e')
            if key in ('cashbox','currency','direction'):
                values={'cashbox':CASHBOXES,'currency':CURRENCIES,'direction':('وارد','منصرف')}[key]
                widget=RoundedCombo(cell,values=values,state='readonly',width=18)
            else:
                widget=RoundedEntry(cell,width=18)
            widget.pack(fill='x');fields[key]=widget
        reason=RoundedEntry(outer,width=60,placeholder_text='سبب التصحيح أو الإلغاء (إجباري)');reason.pack(fill='x',pady=8)
        def load_original():
            try:
                mid=int(movement_entry.get())
                with connection() as con: item=_movement(con,mid)
                for key,widget in fields.items():
                    if isinstance(widget,RoundedCombo):widget.set(str(item[key]))
                    else:
                        widget.delete(0,'end');widget.insert(0,format_money(item[key]).replace(',','') if key=='amount' else str(item[key]))
                if (item['reference'] or '').startswith('TRF:'):
                    request_kind.set('إلغاء')
                messagebox.showinfo('تم التحميل',f"المستند: {item['reference']} | {item['direction']} {format_money(item['amount'])} {item['currency']}",parent=window)
            except (ValueError,sqlite3.Error) as exc:messagebox.showerror('خطأ',str(exc),parent=window)
        def submit():
            try:
                kind='correct' if request_kind.get()=='تصحيح' else 'cancel'
                proposed={key:widget.get() for key,widget in fields.items()} if kind=='correct' else None
                if not messagebox.askyesno('تأكيد','إرسال الطلب للمراجعة؟ لن يتغير الرصيد قبل الاعتماد.',parent=window):return
                submit_change(int(movement_entry.get()),kind,proposed,reason.get(),self.user['username'])
                messagebox.showinfo('تم','تم إرسال الطلب بنجاح',parent=window);reload_requests()
            except (ValueError,sqlite3.Error) as exc:messagebox.showerror('خطأ',str(exc),parent=window)
        def send_selected():
            ids = table.selection()
            if not ids: return
            draft_id = ids[0]
            draft = next((x for x in list_drafts(path, branch_id=branch_id) if x['draft_id']==draft_id),None)
            if not draft or draft['status']!='draft':
                messagebox.showinfo('المزامنة','المسودة سبق إرسالها',parent=win); return
            from tkinter import simpledialog
            server_url = simpledialog.askstring('سيرفر PMP','عنوان السيرفر HTTPS (بدون /health)',
                                                initialvalue='https://authentic-analysis-production-34a5.up.railway.app',parent=win)
            if not server_url: return
            use_account = messagebox.askyesno('طريقة المصادقة',
                'هل تم إنشاء حساب مركزي لك على السيرفر؟\nنعم: الدخول باسم المستخدم وكلمة المرور.\nلا: رمز الفرع القديم (قد يرجع 403).',parent=win)
            username = password = None
            if use_account:
                username = simpledialog.askstring('الحساب المركزي','اسم المستخدم المركزي:',parent=win)
                if not username: return
                password = simpledialog.askstring('الحساب المركزي','كلمة المرور:',show='*',parent=win)
                if not password: return
            sync_btn.configure(state='disabled', text='جاري الإرسال...')
            def worker():
                try:
                    if use_account:
                        from draft_sync import submit_draft_with_login
                        result=submit_draft_with_login(path,branch_id,draft_id,server_url,username,password)
                    else:
                        from draft_sync import submit_draft
                        result=submit_draft(path,branch_id,draft_id,server_url)
                    outcome=('ok',result['status'])
                except Exception as exc:
                    outcome=('error',str(exc))
                def done():
                    if not win.winfo_exists(): return
                    sync_btn.configure(state='normal',text='إرسال المحددة للسيرفر')
                    if outcome[0]=='ok':
                        clear();reload()
                        messagebox.showinfo('المزامنة','تم استلام المسودة للمراجعة: '+outcome[1],parent=win)
                    else: messagebox.showerror('المزامنة',outcome[1],parent=win)
                self.root.after(0,done)
            threading.Thread(target=worker,daemon=True).start()
        buttons=ctk.CTkFrame(outer,fg_color='transparent');buttons.pack(pady=8)
        RoundedButton(buttons,text='تحميل الحركة',command=load_original).pack(side='right',padx=5)
        RoundedButton(buttons,text='إرسال الطلب',command=submit).pack(side='right',padx=5)
        ctk.CTkLabel(outer,text='سجل الطلبات (المدير ونائب المدير فقط يمكنهما الاعتماد أو الرفض)',font=('Segoe UI',14,'bold')).pack(pady=8)
        frame=ctk.CTkFrame(outer);frame.pack(fill='x')
        columns=('id','movement','type','requester','status','reason','created','decider')
        tree=ttk.Treeview(frame,columns=columns,show='headings',height=10)
        for key,title,width in [('id','طلب',55),('movement','حركة',60),('type','النوع',70),('requester','الطالب',110),('status','الحالة',90),('reason','السبب',230),('created','التاريخ',150),('decider','المعتمد',110)]:
            tree.heading(key,text=title);tree.column(key,width=width,anchor='center')
        tree.pack(side='left',fill='both',expand=True)
        ttk.Scrollbar(frame,orient='vertical',command=tree.yview).pack(side='right',fill='y')
        filterbar=ctk.CTkFrame(outer,fg_color='transparent');filterbar.pack(fill='x',pady=5)
        ctk.CTkLabel(filterbar,text='بحث برقم المستند أو الحركة').pack(side='right',padx=4)
        req_search=RoundedEntry(filterbar,width=19);req_search.pack(side='right',padx=4)
        req_type=RoundedCombo(filterbar,values=['كل الأنواع','تصحيح','إلغاء'],state='readonly',width=13)
        req_type.set('كل الأنواع');req_type.pack(side='right',padx=4)
        req_status=RoundedCombo(filterbar,values=['كل الحالات','pending','approved','rejected'],state='readonly',width=14)
        req_status.set('كل الحالات');req_status.pack(side='right',padx=4)
        def get_filtered_requests():
            with connection() as con:
                rows=con.execute('''SELECT r.id,r.movement_id,r.request_type,r.requested_by,r.status,r.reason,
                    r.created_at,COALESCE(r.decided_by,''),COALESCE(m.reference,''),
                    COALESCE(r.decided_at,''),COALESCE(r.decision_note,'')
                    FROM change_requests r LEFT JOIN movements m ON m.id=r.movement_id
                    ORDER BY r.id DESC''').fetchall()
            q=req_search.get().strip().lower()
            kind={'تصحيح':'correct','إلغاء':'cancel'}.get(req_type.get())
            state=req_status.get()
            return [r for r in rows if
                (self.user['role']!='accountant' or r[3]==self.user['username']) and
                (not q or q in str(r[1]).lower() or q in str(r[8]).lower()) and
                (not kind or r[2]==kind) and
                (state=='كل الحالات' or r[4]==state)]
        def reload_requests():
            for iid in tree.get_children():tree.delete(iid)
            for row in get_filtered_requests():
                tree.insert('','end',values=row[:8])
        def decision(approve):
            chosen=tree.selection()
            if not chosen:return messagebox.showwarning('تنبيه','حدد طلباً من الجدول',parent=window)
            rid=int(tree.item(chosen[0],'values')[0])
            if not messagebox.askyesno('تأكيد الاعتماد' if approve else 'تأكيد الرفض',f'هل تريد {"اعتماد" if approve else "رفض"} الطلب {rid}؟',parent=window):return
            try:
                decide_change(rid,approve,self.user['username'],self.user['role'])
                self.refresh();reload_requests()
                messagebox.showinfo('تم','تمت معالجة الطلب بنجاح',parent=window)
            except (ValueError,PermissionError,sqlite3.Error) as exc:messagebox.showerror('تعذر التنفيذ',str(exc),parent=window)
        def export_requests():
            rows=get_filtered_requests()
            filename=filedialog.asksaveasfilename(parent=window,defaultextension='.csv',
                filetypes=[('CSV','*.csv')],initialfile='PMP_change_audit_V7_3.csv')
            if not filename:return
            try:
                with open(filename,'w',encoding='utf-8-sig',newline='') as f:
                    writer=csv.writer(f)
                    writer.writerow(['رقم الطلب','رقم الحركة','نوع الطلب','مقدم الطلب','الحالة','السبب',
                                     'تاريخ الطلب','المعتمد','رقم المستند الأصلي','تاريخ القرار','ملاحظة القرار'])
                    writer.writerows(rows)
                messagebox.showinfo('تم',f'تم تصدير {len(rows)} طلب',parent=window)
            except OSError as exc:messagebox.showerror('تعذر التصدير',str(exc),parent=window)
        RoundedButton(filterbar,text='تطبيق الفلاتر',command=reload_requests,width=115).pack(side='right',padx=4)
        RoundedButton(outer,text='تصدير سجل الطلبات CSV',command=export_requests,width=210).pack(pady=6)
        if self.user['role'] in ('manager','deputy'):
            decisionrow=ctk.CTkFrame(outer,fg_color='transparent');decisionrow.pack(pady=10)
            RoundedButton(decisionrow,text='اعتماد الطلب',command=lambda:decision(True)).pack(side='right',padx=6)
            RoundedButton(decisionrow,text='رفض الطلب',fg_color='#B42335',command=lambda:decision(False)).pack(side='right',padx=6)
        reload_requests()

    def reset_search(self):
        """Reset view filters only; no financial records are modified."""
        self.search_text.set('')
        self.filter_cashbox.set('كل الخزن')
        self.filter_currency.set('كل العملات')
        self.filter_direction.set('كل الأنواع')
        self.load_movements()

    def _clear_field_error(self, key):
        widget = self.fields.get(key)
        if widget is not None:
            widget.configure(border_color='#B8CBE3')
        label = self.field_error_labels.get(key)
        if label is not None:
            label.configure(text='')

    def _mark_field_error(self, key, reason):
        widget = self.fields.get(key)
        if widget is not None:
            widget.configure(border_color='#C62828', border_width=2)
        label = self.field_error_labels.get(key)
        if label is not None:
            label.configure(text=reason)

    def _validate_form_fields(self):
        """UI validation; original accounting checks remain in save()."""
        for key in self.required_fields:
            self._clear_field_error(key)
        values = {key: widget.get().strip() for key, widget in self.fields.items()}
        errors = {}
        for key in self.required_fields:
            if not values[key]:
                errors[key] = 'هذا الحقل مطلوب'
        if values['date']:
            try:
                date.fromisoformat(values['date'])
            except ValueError:
                errors['date'] = 'صيغة التاريخ المطلوبة YYYY-MM-DD'
        if values['amount']:
            try:
                amount = Decimal(values['amount'])
                if not amount.is_finite() or amount <= 0 or amount.as_tuple().exponent < -2:
                    errors['amount'] = 'مبلغ موجب بحد أقصى منزلتين عشريتين'
            except InvalidOperation:
                errors['amount'] = 'أدخل مبلغاً رقمياً صحيحاً'
        for key, reason in errors.items():
            self._mark_field_error(key, reason)
        if errors:
            self.fields[next(key for key in self.fields if key in errors)].focus_set()
        return not errors

    def _paint_profile_avatar(self):
        """Draw the signed-in employee photo, with initials as a safe fallback."""
        avatar = self.profile_avatar
        avatar.delete('all')
        avatar.create_oval(2, 2, 37, 37, fill='#FFFFFF', outline='#D3E0F2', width=2)
        self._avatar_image = None
        try:
            with connection() as con:
                row = con.execute('SELECT photo_path FROM users WHERE username=?',
                                  (self.user['username'],)).fetchone()
            path = Path(row[0]) if row and row[0] else None
            if path and path.is_file() and path.resolve().is_relative_to((APP_DIR / 'profile_photos').resolve()):
                from PIL import Image, ImageTk, ImageDraw
                im = Image.open(path).convert('RGBA').resize((34, 34), Image.Resampling.LANCZOS)
                mask = Image.new('L', (34, 34), 0)
                ImageDraw.Draw(mask).ellipse((0, 0, 33, 33), fill=255)
                im.putalpha(mask)
                self._avatar_image = ImageTk.PhotoImage(im)
                avatar.create_image(19, 19, image=self._avatar_image)
                return
        except (OSError, ValueError, ImportError, sqlite3.Error):
            pass
        avatar.create_text(19, 19, text=(str(self.user.get('display_name') or self.user['username'])[:1] or 'P'),
                           fill=M3['primary'], font=('Segoe UI', 16, 'bold'))

    def profile_dialog(self):
        """Figma 'الملف الشخصي': avatar, contact details and password change in one card."""
        import pmp_widgets as W
        with connection() as con:
            row = con.execute('SELECT display_name,phone,email,address,birth_date,job_title,photo_path FROM users WHERE username=?',
                              (self.user['username'],)).fetchone()
        if not row:
            return
        self._route('profile_dialog')
        self._card_heading = ''
        popup = self._embedded_screen('profile_dialog')
        scroll = ctk.CTkScrollableFrame(popup, fg_color='transparent')
        scroll.pack(fill='both', expand=True, padx=60, pady=(0, 12))
        display_name = row[0] or self.user['username']
        initials = ''.join(part[:1] for part in str(display_name).split()[:2]).upper() or 'P'
        ctk.CTkLabel(scroll, text=initials, width=104, height=104, corner_radius=52, fg_color='#DDEAFE',
                     text_color=M3['primary'], font=('Segoe UI', 30, 'bold')).pack(pady=(14, 8))
        name_label = ctk.CTkLabel(scroll, text=display_name, font=('Segoe UI', 24, 'bold'), text_color=M3['ink'])
        name_label.pack()
        ctk.CTkLabel(scroll, text=f"اسم المستخدم: {self.user['username']}   |   المسمى الوظيفي: {row[5] or 'موظف'}",
                     font=('Segoe UI', 12), text_color=M3['muted']).pack(pady=(2, 14))
        grid = W.FormGrid(scroll, columns=2)
        grid.pack(fill='x')
        fields = {}
        def entry(label, value, placeholder, show=None):
            widget = grid.add(label, lambda c: W.field_entry(c, placeholder, show=show))
            if value:
                widget.insert(0, value)
            fields[label] = widget
        entry('الاسم الظاهر', row[0], 'أدخل الاسم الظاهر')
        entry('رقم الهاتف', row[1], 'أدخل رقم الهاتف')
        entry('البريد الإلكتروني', row[2], 'أدخل البريد الإلكتروني')
        entry('العنوان', row[3], 'أدخل العنوان')
        entry('تاريخ الميلاد', row[4], 'YYYY-MM-DD')
        entry('كلمة المرور الحالية', '', '••••••••', show='•')
        entry('كلمة مرور جديدة', '', '12 حرفاً على الأقل', show='•')
        chosen = {'path': None}
        photo_label = ctk.CTkLabel(scroll, text='', text_color=M3['muted'], font=('Segoe UI', 11))
        def choose_photo():
            filename = filedialog.askopenfilename(parent=self.root, title='اختيار صورة شخصية',
                                                   filetypes=[('صور', '*.jpg *.jpeg *.png')])
            if filename:
                try:
                    from PIL import Image
                    with Image.open(filename) as im:
                        im.verify()
                    if Path(filename).stat().st_size > 10 * 1024 * 1024:
                        raise ValueError('الصورة أكبر من 10 ميجابايت')
                except (OSError, ValueError) as exc:
                    messagebox.showerror('الصورة', f'تعذر قراءة الصورة: {exc}', parent=self.root)
                    return
                chosen['path'] = filename
                photo_label.configure(text='الصورة المختارة: ' + Path(filename).name)
        ctk.CTkButton(scroll, text='اختيار صورة شخصية', command=choose_photo, height=36, width=190,
                      corner_radius=10, fg_color=M3['table_head'], hover_color='#DCE8FA',
                      text_color=M3['ink'], font=('Segoe UI', 12, 'bold')).pack(pady=(4, 2))
        photo_label.pack()
        def save():
            display = fields['الاسم الظاهر'].get().strip()
            if not display:
                messagebox.showerror('البيانات', 'الاسم الظاهر مطلوب', parent=self.root)
                return
            email = fields['البريد الإلكتروني'].get().strip()
            if email and ('@' not in email or '.' not in email.split('@')[-1]):
                messagebox.showerror('البيانات', 'راجع صيغة البريد الإلكتروني', parent=self.root)
                return
            current_pw = fields['كلمة المرور الحالية'].get()
            new_pw = fields['كلمة مرور جديدة'].get()
            if new_pw or current_pw:
                if not (new_pw and current_pw):
                    messagebox.showerror('كلمة المرور', 'لتغيير كلمة المرور أدخل الحالية والجديدة معاً', parent=self.root)
                    return
            photo_path = row[6]
            if chosen['path']:
                try:
                    from PIL import Image
                    photo_dir = APP_DIR / 'profile_photos'
                    photo_dir.mkdir(parents=True, exist_ok=True)
                    destination = photo_dir / (hashlib.sha256(self.user['username'].encode()).hexdigest()[:24] + '.png')
                    temp_path = photo_dir / (destination.stem + '.tmp.png')
                    with Image.open(chosen['path']) as im:
                        im.convert('RGB').resize((256, 256), Image.Resampling.LANCZOS).save(temp_path, format='PNG')
                    os.replace(temp_path, destination)
                    photo_path = str(destination)
                except (OSError, ValueError) as exc:
                    messagebox.showerror('الصورة', f'تعذر حفظ الصورة: {exc}', parent=self.root)
                    return
            if new_pw:
                try:
                    change_user_password(self.user['username'], current_pw, new_pw)
                except ValueError as exc:
                    messagebox.showerror('كلمة المرور', str(exc), parent=self.root)
                    return
            with connection() as con:
                con.execute('UPDATE users SET display_name=?,phone=?,email=?,address=?,birth_date=?,photo_path=? WHERE username=?',
                            (display, fields['رقم الهاتف'].get().strip(), email,
                             fields['العنوان'].get().strip(), fields['تاريخ الميلاد'].get().strip(),
                             photo_path, self.user['username']))
            self.user['display_name'] = display
            self.profile_name_label.configure(text=f"{first_name(display)}  |  {self.user.get('job_title') or 'موظف'}")
            self._paint_profile_avatar()
            log_action(self.user['username'], 'PROFILE_UPDATE', 'تعديل البيانات الشخصية')
            for key in ('كلمة المرور الحالية', 'كلمة مرور جديدة'):
                fields[key].delete(0, 'end')
            name_label.configure(text=display)
            messagebox.showinfo('الملف الشخصي', 'تم حفظ بياناتك بنجاح' + ('، وتم تغيير كلمة المرور' if new_pw else ''), parent=self.root)
        W.primary_button(scroll, 'حفظ التغييرات', save, width=300).pack(pady=(16, 14))

    def settings_dialog(self):
        popup = ctk.CTkToplevel(self.root)
        popup.title('PMP | الإعدادات')
        popup.geometry('580x610')
        popup.transient(self.root)
        popup.grab_set()
        panel = ctk.CTkScrollableFrame(popup, fg_color='transparent')
        panel.pack(fill='both', expand=True, padx=15, pady=15)
        ctk.CTkLabel(panel, text='الإعدادات', font=('Segoe UI', 23, 'bold'),
                     text_color=M3['primary']).pack(pady=14)
        def item(label, callback):
            def launch():
                popup.grab_release()
                popup.destroy()
                callback()
            ctk.CTkButton(panel, text=label, anchor='e', height=44,
                          fg_color=M3['surface'], text_color=M3['ink'],
                          hover_color='#E4ECF7', command=launch).pack(fill='x', padx=12, pady=5)
        item('الملف الشخصي', self.profile_dialog)
        item('تحديث الأرصدة', self.refresh)
        if self.user['role'] in ('manager', 'deputy'):
            item('النسخ الاحتياطي والاسترداد', self.backup_database)
        if self.user['role'] == 'manager':
            item('إدارة المستخدمين', self.add_user)
            item('إعادة تعيين كلمة المرور', self.reset_password_dialog)
            item('سجل التدقيق', self.show_audit)
            item('فحص سلامة البيانات', self.database_health_dialog)
        ctk.CTkLabel(panel, text='تظهر الخيارات الإدارية حسب صلاحيات المستخدم',
                     text_color=M3['muted']).pack(pady=18)

    def logout(self):
        if not messagebox.askyesno('تسجيل الخروج', 'هل تريد تسجيل الخروج من النظام؟', parent=self.root):
            return
        log_action(self.user['username'], 'LOGOUT', 'تسجيل الخروج')
        if self.search_after_id is not None:
            self.root.after_cancel(self.search_after_id)
            self.search_after_id = None
        for widget in self.root.winfo_children():
            widget.destroy()
        Login(self.root)

    def schedule_search(self, *_args):
        if self.search_after_id is not None:
            self.root.after_cancel(self.search_after_id)
        self.search_after_id = self.root.after(180, self._apply_search)

    def _apply_search(self):
        self.search_after_id = None
        if self.tree.winfo_exists():
            self.load_movements()

    def _filtered_movement_rows(self, limit=None):
        query = """SELECT id, COALESCE(reference, ''), tx_date, cashbox,
                          currency, direction, amount, COALESCE(description, '')
                   FROM movements WHERE 1=1"""
        params = []
        term = self.search_text.get().strip()
        if term:
            query += " AND (COALESCE(reference,'') LIKE ? OR COALESCE(description,'') LIKE ?)"
            params.extend([f'%{term}%', f'%{term}%'])
        box = self.filter_cashbox.get()
        if box != 'كل الخزن':
            query += ' AND cashbox=?'; params.append(box)
        currency = self.filter_currency.get()
        if currency != 'كل العملات':
            query += ' AND currency=?'; params.append(currency)
        direction = self.filter_direction.get()
        if direction != 'كل الأنواع':
            query += ' AND direction=?'; params.append(direction)
        query += ' ORDER BY id DESC'
        if limit is not None:
            query += ' LIMIT ?'; params.append(limit)
        with connection() as con:
            return con.execute(query, params).fetchall()

    def load_movements(self):
        rows = self._filtered_movement_rows(limit=500)
        selected_values = [self.tree.item(i, 'values')[0] for i in self.tree.selection()]
        signature = tuple(tuple(row) for row in rows)
        if getattr(self, '_movement_rows_signature', None) == signature and self.tree.winfo_exists():
            return
        self._movement_rows_signature = signature
        existing = self.tree.get_children()
        if existing:
            self.tree.delete(*existing)  # one Tcl call instead of up to 500
        for row in rows:
            shown = list(row)
            shown[6] = format_money(row[6])
            iid = self.tree.insert('', 'end', values=shown,
                                   tags=('incoming' if row[5] == 'وارد' else 'outgoing',))
            if str(row[0]) in selected_values:
                self.tree.selection_add(iid)

    # ------------------------------------------------------------------
    # V7.9.2: customers and invoices screens
    # ------------------------------------------------------------------
    def _tool_window(self, title, geometry):
        return self._embedded_screen(title)

    def _ask_text(self, parent, title, prompt):
        """Small modal prompt that returns the typed text (or None when cancelled)."""
        win = ctk.CTkToplevel(parent)
        win.title(title)
        win.geometry('470x215')
        win.transient(self.root)
        win.configure(fg_color=M3['background'])
        result = {'value': None}
        ctk.CTkLabel(win, text=prompt, font=('Segoe UI', 12, 'bold'), text_color=M3['ink'],
                     wraplength=420, anchor='e', justify='right').pack(fill='x', padx=22, pady=(22, 8))
        entry = RoundedEntry(win, width=45)
        entry.pack(fill='x', padx=22)
        def ok():
            value = entry.get().strip()
            if not value:
                return
            result['value'] = value
            win.destroy()
        row = ctk.CTkFrame(win, fg_color='transparent')
        row.pack(fill='x', padx=22, pady=18)
        RoundedButton(row, text='تأكيد', command=ok, width=110).pack(side='right', padx=5)
        RoundedButton(row, text='إلغاء', command=win.destroy, width=90, fg_color='#E5EDF7',
                      hover_color='#D5E1F0', text_color=M3['ink']).pack(side='right', padx=5)
        win.after(150, lambda: (win.grab_set(), entry.focus_set()))
        parent.wait_window(win)
        return result['value']

    def _customer_choices(self, active_only=True):
        """Returns (labels, label->customer id)."""
        with connection() as con:
            rows = con.execute('SELECT id,name,phone FROM customers' + (' WHERE active=1' if active_only else '') + ' ORDER BY name').fetchall()
        mapping = {}
        for cid, name, phone in rows:
            mapping[f'{name}' + (f' — {phone}' if phone else '') + f'  [#{cid}]'] = cid
        return list(mapping), mapping

    @staticmethod
    def _money_by_currency(amounts):
        """amounts: iterable of (currency, Decimal) -> '1,000 SDG | 20 USD'."""
        totals = {}
        for currency, value in amounts:
            totals[currency] = totals.get(currency, Decimal('0')) + value
        parts = [f'{format_money(v)} {c}' for c, v in sorted(totals.items()) if v != 0]
        return '  |  '.join(parts) if parts else '0'


    def price_poster_dialog(self):
        """Edit poster-only price list and export the exact supplied PMP background."""
        from price_poster import WEIGHTS, KINDS, load_prices, save_prices, render_poster
        from PIL import ImageTk
        db_path = DB
        try:
            prices, discount = load_prices(db_path)
        except Exception as exc:
            messagebox.showerror('قائمة الأسعار', str(exc))
            return
        win = self._embedded_screen('price_poster_dialog')
        scroll = ctk.CTkScrollableFrame(win, fg_color='transparent')
        scroll.pack(fill='both', expand=True, padx=12, pady=8)
        ctk.CTkLabel(scroll, text='أسعار بوستر PMP — أوزان العبوات 250 و900',
                     font=('Segoe UI', 20, 'bold'), text_color=M3['ink']).pack(pady=12)
        ctk.CTkLabel(scroll, text='هذه الأسعار للإعلانات فقط، ولا تغيّر أسعار الفواتير.',
                     font=('Segoe UI', 12), text_color=M3['muted']).pack(pady=2)
        grid = ctk.CTkFrame(scroll, fg_color=M3['surface'], corner_radius=15)
        grid.pack(pady=10, padx=10, fill='x')
        fields = {}
        for col, weight in enumerate(WEIGHTS):
            column = ctk.CTkFrame(grid, fg_color='transparent')
            column.grid(row=0, column=col, padx=22, pady=14, sticky='nsew')
            grid.grid_columnconfigure(col, weight=1)
            ctk.CTkLabel(column, text=f'وزن العبوة {weight}', font=('Segoe UI', 19, 'bold'),
                         text_color=M3['primary']).pack(pady=7)
            for kind in KINDS:
                row = ctk.CTkFrame(column, fg_color='transparent')
                row.pack(fill='x', pady=4)
                ctk.CTkLabel(row, text=kind, width=85, anchor='e',
                             text_color=M3['ink']).pack(side='right', padx=5)
                entry = ctk.CTkEntry(row, justify='center', width=150)
                entry.insert(0, str(prices[(weight,kind)]))
                entry.pack(side='right', padx=5)
                fields[(weight,kind)] = entry
        discount_row = ctk.CTkFrame(scroll, fg_color='transparent')
        discount_row.pack(pady=10)
        ctk.CTkLabel(discount_row, text='نسبة الخصم %', text_color=M3['ink']).pack(side='right', padx=8)
        discount_entry = ctk.CTkEntry(discount_row, width=90, justify='center')
        discount_entry.insert(0, str(discount))
        discount_entry.pack(side='right')
        template = APP_DIR/'pmp_price_poster.jpg'
        def collect():
            try:
                result = {}
                for key, entry in fields.items():
                    value = entry.get().strip().replace(',', '')
                    if not value.isdigit():
                        raise ValueError('كل الأسعار لازم تكون أرقاماً صحيحة غير سالبة')
                    result[key] = int(value)
                value = discount_entry.get().strip()
                if not value.isdigit() or not 0 <= int(value) <= 100:
                    raise ValueError('نسبة الخصم لازم تكون بين 0 و100')
                return result, int(value)
            except Exception as exc:
                messagebox.showerror('خطأ في البيانات', str(exc))
                return None
        def save():
            values = collect()
            if values is None: return
            try:
                save_prices(db_path, *values)
                messagebox.showinfo('تم الحفظ', 'تم حفظ أسعار البوستر ونسبة الخصم بنجاح')
            except Exception as exc:
                messagebox.showerror('خطأ', str(exc))
        def export(kind):
            values = collect()
            if values is None: return
            try:
                if not template.exists():
                    raise FileNotFoundError('قالب البوستر غير موجود داخل مجلد البرنامج')
                image = render_poster(template, *values)
                if kind == 'preview':
                    # Preview at a readable size without opening a second app page.
                    popup = ctk.CTkToplevel(self.root)
                    popup.title('معاينة بوستر PMP')
                    popup.geometry('550x800')
                    frame = ctk.CTkScrollableFrame(popup)
                    frame.pack(fill='both', expand=True)
                    from PIL import ImageTk
                    thumbnail = image.resize((480,720))
                    photo = ImageTk.PhotoImage(thumbnail)
                    label = tk.Label(frame, image=photo)
                    label.image = photo
                    label.pack(pady=10)
                    popup.lift()
                    return
                if kind == 'png':
                    path = filedialog.asksaveasfilename(title='حفظ البوستر', defaultextension='.png',
                        filetypes=[('PNG Image','*.png')], initialfile='PMP_Prices_Poster.png')
                    if path: image.save(path, format='PNG')
                else:
                    path = filedialog.asksaveasfilename(title='تصدير البوستر PDF', defaultextension='.pdf',
                        filetypes=[('PDF Document','*.pdf')], initialfile='PMP_Prices_Poster.pdf')
                    if path:
                        from reportlab.pdfgen import canvas
                        from reportlab.lib.utils import ImageReader
                        c = canvas.Canvas(path, pagesize=(image.width, image.height))
                        c.drawImage(ImageReader(image), 0, 0, width=image.width, height=image.height)
                        c.showPage()
                        c.save()
                if path: messagebox.showinfo('نجاح', 'تم تصدير البوستر بنجاح')
            except Exception as exc:
                messagebox.showerror('خطأ في التصدير', str(exc))
        actions = ctk.CTkFrame(scroll, fg_color='transparent')
        actions.pack(pady=14)
        for title, action in [('حفظ الأسعار', save), ('معاينة البوستر', lambda: export('preview')),
                              ('تصدير PNG', lambda: export('png')), ('تصدير PDF', lambda: export('pdf'))]:
            ctk.CTkButton(actions, text=title, command=action, width=140).pack(side='right', padx=5)

    def inventory_dialog(self):
        """Unified inventory intake: one atomic operation for product and stock."""
        win=self._tool_window('PMP Paints | المخزن الموحد','1100x790')
        outer=ctk.CTkScrollableFrame(win,fg_color='transparent')
        outer.pack(fill='both',expand=True,padx=26,pady=(0,12))
        ctk.CTkLabel(outer,text='أدخل اللون ووزن العبوة والكمية: الصنف الموجود يزيد رصيده، والجديد يُنشأ تلقائياً',
                     font=('Segoe UI',12),text_color=M3['muted']).pack(pady=(0,12))
        import pmp_widgets as W
        card=ctk.CTkFrame(outer,fg_color=M3['surface'],corner_radius=16)
        card.pack(fill='x',padx=14,pady=8)
        ctk.CTkLabel(card,text='المخزن الموحد',font=('Segoe UI',22,'bold'),text_color=M3['ink']).pack(anchor='e',padx=40,pady=(22,2))
        ctk.CTkLabel(card,text='توريد كراتين',font=('Segoe UI',13),text_color=M3['muted']).pack(anchor='e',padx=40)
        grid=W.FormGrid(card,columns=3)
        grid.pack(fill='x',padx=26,pady=(6,4))
        def field(label,default='',placeholder='',span=1):
            ent=grid.add(label,lambda c:W.field_entry(c,placeholder),span=span)
            if default:ent.insert(0,default)
            return ent
        color=field('اللون',placeholder='اختر / اكتب اللون')
        weight=field('وزن العبوة',placeholder='اختر / اكتب الوزن')
        qty=field('الكمية الواردة (كرتونة)','1')
        purchase=field('سعر الشراء للصنف الجديد (اختياري)','0')
        sale=field('سعر البيع للصنف الجديد (اختياري)','0')
        minimum=field('الحد الأدنى للصنف الجديد','0')
        note=field('بيان التوريد',placeholder='بيان التوريد')
        def build_wh(parent):
            w=RoundedCombo(parent,values=[],state='readonly',width=24)
            w.configure(height=42)
            return w
        wh=grid.add('المخزن',build_wh,span=2)
        whids={}
        def load_wh():
            nonlocal whids
            with closing(connection()) as con:
                whids={name:wid for wid,name in con.execute('SELECT id,name FROM warehouses WHERE active=1 ORDER BY name')}
            wh.configure(values=list(whids))
            if whids and wh.get() not in whids:wh.set(next(iter(whids)))
        load_wh()
        actions=ctk.CTkFrame(card,fg_color='transparent');actions.pack(fill='x',padx=22,pady=(5,17))
        busy=[False]
        def submit():
            if busy[0]:return
            try:
                if wh.get() not in whids:raise ValueError('اختر المخزن أولاً')
                if not messagebox.askyesno('تأكيد التوريد','هل تؤكد إضافة الكراتين إلى الرصيد الموجود؟',parent=win):return
                busy[0]=True;savebtn.configure(state='disabled')
                pid,balance,created=receive_stock_unified(whids[wh.get()],color.get(),weight.get(),qty.get(),
                    self.user['username'],note.get(),purchase.get(),sale.get(),minimum.get())
                reload_table()
                messagebox.showinfo('تم الحفظ',f'{"تم إنشاء الصنف وتوريده" if created else "تمت إضافة الكمية للصنف الموجود"}\nالرصيد الحالي: {balance} كرتونة',parent=win)
                qty.delete(0,'end');qty.insert(0,'1')
            except (ValueError,sqlite3.Error) as e:
                messagebox.showerror('تعذر حفظ التوريد',str(e),parent=win)
            finally:
                busy[0]=False;savebtn.configure(state='normal')
        savebtn=W.primary_button(actions,'إضافة الكمية إلى الرصيد',submit,width=300)
        savebtn.pack(side='right',padx=6)
        def add_wh():
            from tkinter import simpledialog
            name=simpledialog.askstring('مخزن جديد','اسم المخزن الجديد:',parent=win)
            if not name:return
            try:save_warehouse(name,self.user['username']);load_wh();wh.set(name.strip());reload_table()
            except (ValueError,sqlite3.Error) as e:messagebox.showerror('خطأ',str(e),parent=win)
        W.primary_button(actions,'إضافة مخزن',add_wh,width=200,tone='soft').pack(side='right',padx=6)
        tablecard=ctk.CTkFrame(outer,fg_color=M3['surface'],corner_radius=18,border_width=1,border_color=M3['outline'])
        tablecard.pack(fill='both',expand=True,padx=12,pady=12)
        ctk.CTkLabel(tablecard,text='الأرصدة الحالية حسب المخزن واللون والوزن',font=('Segoe UI',16,'bold'),text_color=M3['ink']).pack(anchor='e',padx=16,pady=12)
        tree=ttk.Treeview(tablecard,columns=('wh','color','weight','balance'),show='headings',height=13)
        for k,title,w in [('wh','المخزن',180),('color','اللون',210),('weight','وزن العبوة',150),('balance','الرصيد بالكرتونة',150)]:
            tree.heading(k,text=title);tree.column(k,width=w,anchor='center')
        # V10: search by warehouse, colour or package weight without changing balances.
        stock_search = ctk.StringVar(value='')
        search_bar = ctk.CTkFrame(tablecard, fg_color='transparent')
        search_bar.pack(fill='x', padx=14, pady=(0, 8))
        ctk.CTkLabel(search_bar, text='بحث في المخزون', text_color=M3['ink']).pack(side='right', padx=8)
        stock_search_entry = ctk.CTkEntry(search_bar, textvariable=stock_search, justify='right',
                                          placeholder_text='اللون أو الوزن أو اسم المخزن', width=290)
        stock_search_entry.pack(side='right', padx=6)
        tree.pack(fill='both',expand=True,padx=14,pady=(0,16))
        def reload_table():
            for iid in tree.get_children():tree.delete(iid)
            with closing(connection()) as con:
                rows=con.execute("""SELECT w.id,w.name,p.id,COALESCE(NULLIF(TRIM(p.paint_color),''),p.name),p.package_weight
                    FROM warehouses w JOIN stock_moves m ON m.warehouse_id=w.id
                    JOIN products p ON p.id=m.product_id
                    GROUP BY w.id,p.id ORDER BY w.name,p.name,p.package_weight""").fetchall()
                term = stock_search.get().strip().casefold()
                for wid,wn,pid,pn,pw in rows:
                    if term and term not in f'{wn} {pn} {pw}'.casefold():
                        continue
                    tree.insert('','end',iid=f'stock_{wid}_{pid}',values=(wn,pn,pw,format_money(stock_balance(con,wid,pid))))
        stock_search.trace_add('write', lambda *_: reload_table())
        if self.user['role']=='manager':
            admin=ctk.CTkFrame(outer,fg_color=M3['surface'],corner_radius=18,border_width=1,border_color=M3['outline'])
            admin.pack(fill='x',padx=12,pady=8)
            ctk.CTkLabel(admin,text='أدوات المدير | تصحيح الرصيد وإدارة المخازن',
                font=('Segoe UI',16,'bold'),text_color=M3['primary']).pack(anchor='e',padx=15,pady=10)
            controls=ctk.CTkFrame(admin,fg_color='transparent');controls.pack(fill='x',padx=12,pady=8)
            def selected_stock():
                sel=tree.selection()
                if not sel:raise ValueError('حدد صنفاً من جدول الأرصدة أولاً')
                vals=tree.item(sel[0],'values')
                with closing(connection()) as con:
                    wr=con.execute('SELECT id FROM warehouses WHERE name=?',(vals[0],)).fetchone()
                    matches=con.execute("""SELECT id FROM products WHERE package_weight=? AND
                      (paint_color=? OR (TRIM(paint_color)='' AND name=?))""",(vals[2],vals[1],vals[1])).fetchall()
                if not wr or len(matches)!=1:raise ValueError('الصنف غير محدد بصورة فريدة؛ راجع الأصناف المكررة')
                return wr[0],matches[0][0],vals
            def correction():
                from tkinter import simpledialog
                try:
                    wid,pid,vals=selected_stock()
                    target=simpledialog.askstring('تصحيح الرصيد',f'الرصيد الحالي: {vals[3]} كرتونة\nأدخل الرصيد الصحيح:',parent=win)
                    if target is None:return
                    reason=simpledialog.askstring('سبب التصحيح','اكتب سبب التصحيح (إلزامي):',parent=win)
                    if reason is None:return
                    if not messagebox.askyesno('تأكيد',f'هل تؤكد تصحيح رصيد {vals[1]} إلى {target} كرتونة؟',parent=win):return
                    old,new=correct_stock_balance(wid,pid,target,reason,self.user['username'],self.user['role'])
                    reload_table();messagebox.showinfo('تم',f'الرصيد السابق {old} | الرصيد الجديد {new}',parent=win)
                except (ValueError,PermissionError,sqlite3.Error) as e:messagebox.showerror('خطأ',str(e),parent=win)
            def warehouse_admin(action):
                from tkinter import simpledialog
                try:
                    names=list(whids)
                    if not names:raise ValueError('لا توجد مخازن')
                    current=simpledialog.askstring('إدارة المخازن','اكتب اسم المخزن الحالي بالضبط:',parent=win)
                    if current is None:return
                    if current not in whids:raise ValueError('اسم المخزن غير موجود')
                    wid=whids[current]
                    if action=='rename':
                        new=simpledialog.askstring('تعديل الاسم','الاسم الجديد:',initialvalue=current,parent=win)
                        if new is None:return
                        rename_warehouse_manager(wid,new,self.user['username'],self.user['role'])
                    else:
                        if not messagebox.askyesno('تأكيد الحذف',f'حذف المخزن الفارغ «{current}» نهائياً؟',parent=win):return
                        delete_warehouse_manager(wid,self.user['username'],self.user['role'])
                    load_wh();reload_table();messagebox.showinfo('تم','تم تحديث المخزن',parent=win)
                except (ValueError,PermissionError,sqlite3.Error) as e:messagebox.showerror('خطأ',str(e),parent=win)
            def selected_product_id():
                selected=tree.selection()
                if not selected: raise ValueError('حدد الصنف من جدول الأرصدة أولاً')
                # IDs are stored in the row key; do not resolve by color (duplicates may exist).
                key=selected[0]
                if not key.startswith('stock_'): raise ValueError('تعذر تحديد الصنف')
                return int(key.rsplit('_',1)[1]),tree.item(key,'values')
            def rename_product_color():
                from tkinter import simpledialog
                try:
                    pid,values=selected_product_id()
                    new=simpledialog.askstring('تعديل اللون',f'اللون الحالي: {values[1]}\nاللون الصحيح:',
                        initialvalue=values[1],parent=win)
                    if new is None:return
                    if not messagebox.askyesno('تأكيد تعديل اللون',
                        f'تعديل اللون إلى «{new.strip()}»؟',parent=win):return
                    manager_update_product_color(pid,new,self.user['username'],self.user['role'])
                    reload_table();messagebox.showinfo('تم','تم تعديل لون الصنف',parent=win)
                except (ValueError,PermissionError,sqlite3.Error) as e:messagebox.showerror('خطأ',str(e),parent=win)
            def remove_product():
                try:
                    pid,values=selected_product_id()
                    if not messagebox.askyesno('تأكيد حذف الصنف',
                        f'هل تريد حذف أو إيقاف الصنف «{values[1]}»؟\nالأصناف المرتبطة بسجلات سيتم إيقافها فقط.',parent=win):return
                    result=manager_remove_product(pid,self.user['username'],self.user['role'])
                    reload_table();messagebox.showinfo('تم',result,parent=win)
                except (ValueError,PermissionError,sqlite3.Error) as e:messagebox.showerror('خطأ',str(e),parent=win)
            ctk.CTkButton(controls,text='تعديل لون الصنف',command=rename_product_color,
                fg_color=M3['gold'],text_color=M3['ink']).pack(side='right',padx=6,pady=4)
            ctk.CTkButton(controls,text='حذف / إيقاف صنف',command=remove_product,
                fg_color='#A8324A').pack(side='right',padx=6,pady=4)
            for label,callback in [('تصحيح الرصيد',correction),('تعديل اسم مخزن',lambda:warehouse_admin('rename')),
                                    ('حذف مخزن فارغ',lambda:warehouse_admin('delete'))]:
                ctk.CTkButton(controls,text=label,command=callback,fg_color=M3['primary']).pack(side='right',padx=6,pady=4)
        def export_stock_pdf():
            path=filedialog.asksaveasfilename(parent=win,defaultextension='.pdf',filetypes=[('PDF','*.pdf')],
                initialfile=f'PMP_Stock_{date.today().isoformat()}.pdf')
            if not path:return
            try:
                rows=[tree.item(item,'values') for item in tree.get_children()]
                _pmp_report_pdf(path,'PMP Paints | تقرير أرصدة المخزون',
                    f'التاريخ: {datetime.now():%Y-%m-%d %H:%M} | عدد الأصناف: {len(rows)}',
                    ['الرصيد بالكرتونة','وزن العبوة','اللون','المخزن'],
                    [(r[3],r[2],r[1],r[0]) for r in rows],widths=[175,160,215,225])
                messagebox.showinfo('تم',f'تم تصدير PDF\n{path}',parent=win)
            except Exception as e:messagebox.showerror('تعذر تصدير PDF',str(e),parent=win)
        ctk.CTkButton(outer,text='تصدير تقرير المخزون PDF بشعار PMP',command=export_stock_pdf,
            height=42,fg_color=M3['gold'],text_color=M3['ink']).pack(padx=12,pady=10)
        reload_table()

    def inventory_legacy_dialog(self):
        win=self._tool_window('PMP Paints | إدارة المخزون','1080x800')
        outer=ctk.CTkScrollableFrame(win,fg_color='transparent');outer.pack(fill='both',expand=True,padx=12,pady=12)
        tabs=ctk.CTkTabview(outer,width=990,height=650);tabs.pack(fill='both',expand=True)
        for name in ('المخازن','الأصناف','حركات المخزون','الأرصدة','جدول الألوان والأوزان'):
            tabs.add(name)
        def form_entry(parent,title,default=''):
            block=ctk.CTkFrame(parent,fg_color='transparent');block.pack(side='right',padx=5,pady=5)
            ctk.CTkLabel(block,text=title,font=('Segoe UI',11),anchor='e').pack(fill='x')
            entry=RoundedEntry(block,width=15);entry.pack()
            if default: entry.insert(0,default)
            return entry
        wh_tab=tabs.tab('المخازن')
        wname=form_entry(wh_tab,'اسم المخزن')
        wlist=tk.Listbox(wh_tab,height=12,font=('Segoe UI',12));wlist.pack(fill='x',padx=14,pady=12)
        wh_rows=[]
        def reload_warehouses():
            nonlocal wh_rows
            with connection() as con: wh_rows=con.execute('SELECT id,name,active FROM warehouses ORDER BY id').fetchall()
            wlist.delete(0,'end')
            for wid,name,active in wh_rows: wlist.insert('end',f'{wid} | {name} | '+('نشط' if active else 'موقوف'))
        def selected_wh():
            selection=wlist.curselection()
            if not selection: raise ValueError('حدد المخزن من القائمة')
            return wh_rows[selection[0]]
        def warehouse_action(action):
            try:
                if action=='new': save_warehouse(wname.get(),self.user['username'])
                elif action=='rename': save_warehouse(wname.get(),self.user['username'],selected_wh()[0])
                else:
                    wid,_,active=selected_wh()
                    set_warehouse_active(wid,not active,self.user['username'],self.user['role'])
                reload_warehouses();reload_balances()
            except (ValueError,sqlite3.Error,PermissionError) as e: messagebox.showerror('خطأ',str(e),parent=win)
        buttons=ctk.CTkFrame(wh_tab,fg_color='transparent');buttons.pack()
        for title,action in [('\u200fإضافة مخزن\u200f','new'),('\u200fتعديل الاسم\u200f','rename'),('\u200fإيقاف / تفعيل\u200f','status')]:
            RoundedButton(buttons,text=title,command=lambda a=action:warehouse_action(a)).pack(side='right',padx=5)
        pr_tab=tabs.tab('الأصناف')
        # Explicit grid: seven fields must not share one horizontal pack row.
        # Otherwise the action buttons are placed outside the visible tab on Windows.
        form_panel=ctk.CTkFrame(pr_tab,fg_color='transparent')
        form_panel.pack(fill='x',padx=10,pady=(8,2))
        fields={}
        product_definitions=[('اللون','paint_color',''),('وزن العبوة','package_weight',''),
                             ('سعر الشراء','purchase_price','0'),('سعر البيع','sale_price','0'),('الحد الأدنى (كرتونة)','minimum_stock','0')]
        for idx,(title,key,default) in enumerate(product_definitions):
            row,col=divmod(idx,3)
            cell=ctk.CTkFrame(form_panel,fg_color='transparent')
            cell.grid(row=row,column=2-col,sticky='ew',padx=5,pady=4)
            ctk.CTkLabel(cell,text=title,font=('Segoe UI',11),anchor='e').pack(fill='x')
            fields[key]=RoundedEntry(cell,width=13)
            fields[key].pack(fill='x')
            if default:fields[key].insert(0,default)
        for col in range(3):form_panel.grid_columnconfigure(col,weight=1)
        pbuttons=ctk.CTkFrame(pr_tab,fg_color='transparent')
        pbuttons.pack(fill='x',padx=12,pady=(8,10))
        RoundedButton(pbuttons,text='حفظ / إضافة صنف',width=170,
                      command=lambda:product_action(False)).pack(side='right',padx=5)
        RoundedButton(pbuttons,text='تعديل الصنف المحدد',width=170,
                      command=lambda:product_action(True)).pack(side='right',padx=5)
        plist=tk.Listbox(pr_tab,height=7,font=('Segoe UI',11))
        plist.pack(fill='both',expand=True,padx=12,pady=8)
        product_rows=[]
        def reload_products():
            nonlocal product_rows
            with connection() as con:
                product_rows=con.execute('SELECT id,sku,name,category,unit,purchase_price,sale_price,minimum_stock,active,package_weight,paint_color FROM products ORDER BY id').fetchall()
            plist.delete(0,'end')
            for row in product_rows: plist.insert('end',f'اللون: {row[10] or row[2]} | وزن العبوة: {row[9] or "غير محدد"} | سعر البيع {row[6]} | الحد الأدنى {row[7]} كرتونة')
        def select_product(_=None):
            sel=plist.curselection()
            if not sel:return
            row=product_rows[sel[0]]
            for key,val in [('paint_color',row[10] or row[2]),('package_weight',row[9]),('purchase_price',row[5]),('sale_price',row[6]),('minimum_stock',row[7])]:
                fields[key].delete(0,'end');fields[key].insert(0,str(val or ''))
        plist.bind('<<ListboxSelect>>',select_product)
        def product_action(edit):
            try:
                sel=plist.curselection()
                if edit and not sel: raise ValueError('حدد الصنف المراد تعديله')
                pid=product_rows[sel[0]][0] if edit else None
                save_product(dict({key:entry.get() for key,entry in fields.items()},
                    name=fields['paint_color'].get().strip(),
                    sku=product_rows[sel[0]][1] if edit else '',
                    category='بوهيات',unit='كرتونة'),self.user['username'],pid)
                reload_products();reload_balances()
            except (ValueError,sqlite3.Error) as e:messagebox.showerror('خطأ',str(e),parent=win)
        ctk.CTkLabel(pr_tab,text='لإضافة كراتين للمخزن افتح تبويب «حركات المخزون» واختر «إضافة كراتين إلى الرصيد». الكمية تُضاف للموجود ولا تستبدله.',
                     font=('Segoe UI',11),text_color='#164B79',wraplength=800).pack(pady=5)
        mv_tab=tabs.tab('حركات المخزون')
        mproduct=ttk.Combobox(mv_tab,state='readonly',width=45,font=('Segoe UI',11),justify='right')
        mstore=ttk.Combobox(mv_tab,state='readonly',width=40,font=('Segoe UI',11),justify='right')
        for title,control in [('الصنف',mproduct),('المخزن',mstore)]:
            ctk.CTkLabel(mv_tab,text=title).pack(pady=(14,0));control.pack(ipady=5)
        mqty=form_entry(mv_tab,'عدد الكراتين المراد إضافتها','1')
        mnote=form_entry(mv_tab,'البيان')
        product_ids={};warehouse_ids={}
        def reload_choices():
            nonlocal product_ids,warehouse_ids
            with connection() as con:
                product_ids={f'{color or name} | {weight or "بدون وزن"} | رقم {pid}':pid for pid,sku,name,color,weight in con.execute('SELECT id,sku,name,paint_color,package_weight FROM products WHERE active=1 ORDER BY name')}
                warehouse_ids={name:wid for wid,name in con.execute('SELECT id,name FROM warehouses WHERE active=1 ORDER BY name')}
            mproduct['values']=list(product_ids);mstore['values']=list(warehouse_ids)
        def stock_action(kind):
            try:
                if not product_ids.get(mproduct.get()) or not warehouse_ids.get(mstore.get()):
                    raise ValueError('اختر الصنف والمخزن')
                if not messagebox.askyesno('تأكيد الحركة','هل تؤكد تسجيل حركة المخزون؟',parent=win):return
                post_stock_move(warehouse_ids[mstore.get()],product_ids[mproduct.get()],mqty.get(),kind,mnote.get(),self.user['username'])
                reload_balances();messagebox.showinfo('تم','تم تسجيل حركة المخزون',parent=win)
            except (ValueError,sqlite3.Error) as e:messagebox.showerror('خطأ',str(e),parent=win)
        mbuttons=ctk.CTkFrame(mv_tab,fg_color='transparent');mbuttons.pack(pady=12)
        RoundedButton(mbuttons,text='إضافة كراتين إلى الرصيد',command=lambda:stock_action('IN')).pack(side='right',padx=5)
        RoundedButton(mbuttons,text='صرف بضاعة',fg_color='#B42335',command=lambda:stock_action('OUT')).pack(side='right',padx=5)
        bal_tab=tabs.tab('الأرصدة')
        balance_tree=ttk.Treeview(bal_tab,columns=('warehouse','product','weight','qty','minimum'),show='headings',height=17)
        for key,title,width in [('warehouse','المخزن',200),('product','اللون',250),('weight','وزن العبوة',140),('qty','الرصيد بالكرتونة',130),('minimum','الحد الأدنى',140)]:
            balance_tree.heading(key,text=title);balance_tree.column(key,width=width,anchor='center')
        balance_tree.pack(fill='both',expand=True,padx=12,pady=12)
        def reload_balances():
            reload_choices()
            for item in balance_tree.get_children():balance_tree.delete(item)
            with connection() as con:
                rows=con.execute('SELECT w.id,w.name,p.id,COALESCE(NULLIF(p.paint_color,''),p.name),p.minimum_stock,p.package_weight FROM warehouses w CROSS JOIN products p ORDER BY w.name,p.name').fetchall()
                for wid,wn,pid,pn,minimum,weight in rows:
                    qty=stock_balance(con,wid,pid)
                    if qty or qty<Decimal(minimum):
                        balance_tree.insert('','end',values=(wn,pn,weight or '—',format_money(qty),minimum))
        def export_balances_pdf():
            try:
                from reportlab.platypus import SimpleDocTemplate, Table, TableStyle, Paragraph, Spacer
                from reportlab.lib import colors
                from reportlab.lib.pagesizes import A4, landscape
                from reportlab.lib.styles import ParagraphStyle
                from reportlab.pdfbase import pdfmetrics
                from reportlab.pdfbase.ttfonts import TTFont
                import arabic_reshaper
                from bidi.algorithm import get_display
                path=filedialog.asksaveasfilename(parent=win,defaultextension='.pdf',
                      filetypes=[('PDF Document','*.pdf')],
                      initialfile=f'PMP_stock_balances_{date.today().isoformat()}.pdf')
                if not path:return
                fontfile=_arabic_font_file()
                if not fontfile:raise RuntimeError('لم يتم العثور على خط عربي مناسب لتصدير PDF')
                pdfmetrics.registerFont(TTFont('PMPArabic',fontfile))
                def rtl(v):return get_display(arabic_reshaper.reshape(str(v)))
                style=ParagraphStyle('stockrtl',fontName='PMPArabic',fontSize=9,leading=15,alignment=1)
                def cell(v):return Paragraph(rtl(v),style)
                with connection() as con:
                    rows=con.execute('''SELECT w.id,w.name,p.id,p.sku,p.name,p.package_weight,
                           p.unit,p.minimum_stock FROM warehouses w CROSS JOIN products p
                           ORDER BY w.name,p.name,p.package_weight''').fetchall()
                    results=[]
                    for wid,wn,pid,sku,pn,weight,unit,minimum in rows:
                        qty=stock_balance(con,wid,pid)
                        if qty or qty<Decimal(minimum):
                            results.append((wn,sku,pn,weight or '—',unit,format_money(qty),minimum))
                doc=SimpleDocTemplate(path,pagesize=landscape(A4),rightMargin=24,leftMargin=24,
                                      topMargin=30,bottomMargin=30)
                story=[Paragraph(rtl('PMP Paints | تقرير أرصدة المخزون'),
                          ParagraphStyle('stocktitle',parent=style,fontSize=17,leading=25)),
                       Spacer(1,8),Paragraph(rtl(f'تاريخ التقرير: {datetime.now():%Y-%m-%d %H:%M} | عدد السجلات: {len(results)}'),style),
                       Spacer(1,12)]
                data=[[cell(h) for h in ('الحد الأدنى','الرصيد','الوحدة','وزن العبوة','الصنف','الكود','المخزن')]]
                for wn,sku,pn,weight,unit,qty,minimum in results:
                    data.append([cell(v) for v in (minimum,qty,unit,weight,pn,sku,wn)])
                t=Table(data,colWidths=[78,83,72,105,205,90,148],repeatRows=1)
                t.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),colors.HexColor('#D9E8F6')),
                      ('ROWBACKGROUNDS',(0,1),(-1,-1),[colors.white,colors.HexColor('#EDF4FB')]),
                      ('GRID',(0,0),(-1,-1),0.3,colors.HexColor('#C9D8E7')),
                      ('VALIGN',(0,0),(-1,-1),'MIDDLE'),('TOPPADDING',(0,0),(-1,-1),6),
                      ('BOTTOMPADDING',(0,0),(-1,-1),6)]))
                story.append(t)
                doc.build(story)
                messagebox.showinfo('تم',f'تم تصدير أرصدة المخزون إلى PDF\n{path}',parent=win)
            except Exception as exc:
                messagebox.showerror('خطأ في تصدير PDF',str(exc),parent=win)
        balance_actions=ctk.CTkFrame(bal_tab,fg_color='transparent')
        balance_actions.pack(pady=8)
        RoundedButton(balance_actions,text='تحديث الأرصدة',command=reload_balances).pack(side='right',padx=5)
        RoundedButton(balance_actions,text='تصدير الأرصدة PDF',command=export_balances_pdf).pack(side='right',padx=5)
        # V9.5: cartons-only color-by-package-size pivot, read from the existing stock ledger.
        matrix_tab=tabs.tab('جدول الألوان والأوزان')
        ctk.CTkLabel(matrix_tab,text='جدول كراتين البوهيات حسب اللون ووزن العبوة',
                     font=('Segoe UI',16,'bold'),text_color=M3['ink']).pack(pady=(10,3))
        ctk.CTkLabel(matrix_tab,text='كل رقم يمثل كرتونة كاملة. الأصناف القديمة بلا لون أو وزن لا تدخل في هذا الجدول.',
                     font=('Segoe UI',11),text_color=M3['muted']).pack(pady=(0,8))
        matrix_filter=ctk.CTkFrame(matrix_tab,fg_color='transparent');matrix_filter.pack(fill='x',padx=10)
        ctk.CTkLabel(matrix_filter,text='المخزن:').pack(side='right',padx=5)
        matrix_store=ttk.Combobox(matrix_filter,state='readonly',width=28,justify='right')
        matrix_store.pack(side='right',padx=5)
        matrix_area=ctk.CTkFrame(matrix_tab,fg_color='transparent')
        matrix_area.pack(fill='both',expand=True,padx=10,pady=8)
        matrix_tree=ttk.Treeview(matrix_area,show='headings',height=13)
        mx_y=ttk.Scrollbar(matrix_area,orient='vertical',command=matrix_tree.yview)
        mx_x=ttk.Scrollbar(matrix_area,orient='horizontal',command=matrix_tree.xview)
        matrix_tree.configure(yscrollcommand=mx_y.set,xscrollcommand=mx_x.set)
        mx_y.pack(side='right',fill='y');mx_x.pack(side='bottom',fill='x')
        matrix_tree.pack(side='left',fill='both',expand=True)
        matrix_data={'colors':[],'weights':[],'rows':[],'total':0,'warehouse':'كل المخازن'}
        def matrix_load():
            with connection() as con:
                stores=con.execute('SELECT id,name FROM warehouses ORDER BY name').fetchall()
                choices=['كل المخازن']+[n for _,n in stores]
                old=matrix_store.get()
                matrix_store['values']=choices
                matrix_store.set(old if old in choices else choices[0])
                store_id=next((wid for wid,n in stores if n==matrix_store.get()),None)
                products=con.execute("""SELECT id,paint_color,package_weight FROM products
                    WHERE TRIM(paint_color)<>'' AND TRIM(package_weight)<>'' ORDER BY id""").fetchall()
                colors=sorted({color for _,color,_ in products})
                weights=sorted({weight for _,_,weight in products},key=lambda v:(0 if v=='جالون' else 1 if v=='ربع' else 2,v))
                ledger=con.execute('SELECT warehouse_id,product_id,kind,quantity FROM stock_moves').fetchall()
            product_lookup={pid:(color,weight) for pid,color,weight in products}
            amounts={}
            for wid,pid,kind,qty in ledger:
                if pid not in product_lookup or (store_id is not None and wid!=store_id):continue
                color,weight=product_lookup[pid]
                key=(weight,color)
                amounts[key]=amounts.get(key,Decimal('0'))+(Decimal(qty) if kind in ('IN','RETURN') else -Decimal(qty))
            matrix_data.update(colors=colors,weights=weights,warehouse=matrix_store.get())
            columns=['weight']+[f'c{i}' for i in range(len(colors))]+['total']
            matrix_tree['columns']=columns
            matrix_tree.heading('weight',text='العبوة')
            matrix_tree.column('weight',width=110,minwidth=100,anchor='center',stretch=False)
            for i,color in enumerate(colors):
                matrix_tree.heading(f'c{i}',text=color)
                matrix_tree.column(f'c{i}',width=95,minwidth=75,anchor='center',stretch=False)
            matrix_tree.heading('total',text='عدد الكراتين')
            matrix_tree.column('total',width=130,minwidth=110,anchor='center',stretch=False)
            matrix_tree.delete(*matrix_tree.get_children())
            rows=[]
            for weight in weights:
                values=[amounts.get((weight,color),Decimal('0')) for color in colors]
                total=sum(values,Decimal('0'))
                rows.append((weight,values,total))
                matrix_tree.insert('','end',values=[weight]+[str(v) for v in values]+[str(total)])
            totals=[sum((amounts.get((weight,color),Decimal('0')) for weight in weights),Decimal('0')) for color in colors]
            grand=sum(totals,Decimal('0'))
            matrix_tree.insert('','end',values=['الإجمالي']+[str(v) for v in totals]+[str(grand)],tags=('total',))
            matrix_tree.tag_configure('total',background='#DCE9F8',foreground='#102D50')
            matrix_data.update(rows=rows,totals=totals,total=grand)
        def matrix_export_excel():
            try:
                from openpyxl import Workbook
                from openpyxl.styles import Font,PatternFill,Alignment
                matrix_load()
                path=filedialog.asksaveasfilename(parent=win,defaultextension='.xlsx',filetypes=[('Excel','*.xlsx')],initialfile='PMP_stock_matrix_V9_5.xlsx')
                if not path:return
                wb=Workbook();ws=wb.active;ws.title='أرصدة الكراتين';ws.sheet_view.rightToLeft=True
                ws.append(['المخزن',matrix_data['warehouse']]);ws.append(['تاريخ التقرير',date.today().isoformat()])
                ws.append(['العبوة']+matrix_data['colors']+['عدد الكراتين'])
                for weight,values,total in matrix_data['rows']:
                    ws.append([weight]+[float(v) for v in values]+[float(total)])
                ws.append(['الإجمالي']+[float(v) for v in matrix_data['totals']]+[float(matrix_data['total'])])
                for cell in ws[3]:cell.fill=PatternFill('solid',fgColor='164B87');cell.font=Font(color='FFFFFF',bold=True)
                for cell in ws[ws.max_row]:cell.fill=PatternFill('solid',fgColor='E8D7A5');cell.font=Font(bold=True)
                for column in ws.columns:
                    letter=column[0].column_letter;ws.column_dimensions[letter].width=20
                    for cell in column:cell.alignment=Alignment(horizontal='center')
                wb.save(path)
                messagebox.showinfo('تم',f'تم حفظ التقرير:\n{path}',parent=win)
            except Exception as exc:messagebox.showerror('خطأ',str(exc),parent=win)
        def matrix_export_pdf():
            try:
                from reportlab.platypus import SimpleDocTemplate,Table,TableStyle,Paragraph,Spacer
                from reportlab.lib.pagesizes import A3,landscape
                from reportlab.lib import colors as pdfcolors
                from reportlab.lib.styles import ParagraphStyle
                from reportlab.pdfbase import pdfmetrics
                from reportlab.pdfbase.ttfonts import TTFont
                from xml.sax.saxutils import escape
                import arabic_reshaper
                from bidi.algorithm import get_display
                matrix_load()
                path=filedialog.asksaveasfilename(parent=win,defaultextension='.pdf',filetypes=[('PDF','*.pdf')],initialfile='PMP_stock_matrix_V9_5.pdf')
                if not path:return
                fontfile=_arabic_font_file()
                if not fontfile:raise RuntimeError('يلزم توفر خط عربي لتصدير PDF')
                pdfmetrics.registerFont(TTFont('PMPMatrixArabic',fontfile))
                style=ParagraphStyle('matrix',fontName='PMPMatrixArabic',fontSize=8,leading=12,alignment=1)
                def cell(v):return Paragraph(escape(get_display(arabic_reshaper.reshape(str(v)))),style)
                # Split wide matrices into groups so Arabic text stays legible on paper.
                doc=SimpleDocTemplate(path,pagesize=landscape(A3),leftMargin=20,rightMargin=20,topMargin=24,bottomMargin=24)
                story=[Paragraph(escape(get_display(arabic_reshaper.reshape('PMP Paints | أرصدة الكراتين حسب اللون والوزن'))),style),
                       Paragraph(escape(get_display(arabic_reshaper.reshape('المخزن: '+matrix_data['warehouse']+' | '+date.today().isoformat()))),style),Spacer(1,12)]
                allcolors=matrix_data['colors']
                for offset in range(0,max(1,len(allcolors)),10):
                    colors_chunk=allcolors[offset:offset+10]
                    indexes=list(range(offset,min(offset+10,len(allcolors))))
                    header=['العبوة']+colors_chunk+['مجموع الألوان المعروضة']
                    data=[[cell(v) for v in header]]
                    for weight,values,_ in matrix_data['rows']:
                        nums=[values[i] for i in indexes]
                        data.append([cell(weight)]+[cell(str(n)) for n in nums]+[cell(str(sum(nums,Decimal('0'))))])
                    nums=[matrix_data['totals'][i] for i in indexes]
                    data.append([cell('الإجمالي')]+[cell(str(n)) for n in nums]+[cell(str(sum(nums,Decimal('0'))))])
                    widths=[110]+[max(65, min(95,900/max(1,len(indexes))))]*len(indexes)+[130]
                    t=Table(data,colWidths=widths,repeatRows=1)
                    t.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),pdfcolors.HexColor('#D8E8F8')),
                        ('BACKGROUND',(0,-1),(-1,-1),pdfcolors.HexColor('#F1DFB2')),
                        ('GRID',(0,0),(-1,-1),0.4,pdfcolors.HexColor('#B6C5D6')),
                        ('VALIGN',(0,0),(-1,-1),'MIDDLE'),('TOPPADDING',(0,0),(-1,-1),7),('BOTTOMPADDING',(0,0),(-1,-1),7)]))
                    story.extend([t,Spacer(1,18)])
                doc.build(story)
                messagebox.showinfo('تم',f'تم حفظ التقرير:\n{path}',parent=win)
            except Exception as exc:messagebox.showerror('خطأ',str(exc),parent=win)
        matrix_store.bind('<<ComboboxSelected>>',lambda _e:matrix_load())
        actions=ctk.CTkFrame(matrix_tab,fg_color='transparent');actions.pack(pady=8)
        RoundedButton(actions,text='تحديث الجدول',command=matrix_load).pack(side='right',padx=5)
        RoundedButton(actions,text='تصدير Excel',command=matrix_export_excel).pack(side='right',padx=5)
        RoundedButton(actions,text='تصدير PDF',command=matrix_export_pdf).pack(side='right',padx=5)
        matrix_load()

        reload_warehouses();reload_products();reload_balances()

    def reports_v81_dialog(self):
        win=self._embedded_screen('reports_v81_dialog')
        win.title('PMP | التقارير');win.geometry('1180x740');win.minsize(900,600)
        win.transient(self.root)
        import pmp_widgets as W
        kinds={'المبيعات (قبل خصم الفاتورة)':'sales','كشف حساب العملاء':'statement','المديونيات':'debts','حركة المخزون':'stock'}
        type_var=tk.StringVar(value=next(iter(kinds)))
        card=ctk.CTkFrame(win,fg_color='white',corner_radius=16)
        card.pack(fill='x',padx=26,pady=(0,8))
        ctk.CTkLabel(card,text='التقارير والمراجعة',font=('Segoe UI',22,'bold'),text_color=M3['ink'],
                     anchor='e').pack(fill='x',padx=40,pady=(22,2))
        filters=W.FormGrid(card,columns=3)
        filters.pack(fill='x',padx=26,pady=(4,2))
        fields={}
        def build_kind(parent):
            w=RoundedCombo(parent,variable=type_var,values=list(kinds),state='readonly',width=24)
            w.configure(height=42);return w
        filters.add('نوع التقرير',build_kind)
        fields['start']=filters.add('من تاريخ (YYYY-MM-DD)',lambda c:W.field_entry(c,'اختر تاريخ البداية'))
        fields['end']=filters.add('إلى تاريخ (YYYY-MM-DD)',lambda c:W.field_entry(c,'اختر تاريخ النهاية'))
        fields['customer']=filters.add('العميل',lambda c:W.field_entry(c,'اختياري'))
        fields['product']=filters.add('الصنف',lambda c:W.field_entry(c,'اختياري'))
        fields['warehouse']=filters.add('المخزن',lambda c:W.field_entry(c,'اختياري'))
        controls=ctk.CTkFrame(card,fg_color='transparent');controls.pack(pady=(6,18))
        summary_label=ctk.CTkLabel(win,text='اختر التقرير ثم اضغط عرض',anchor='e',justify='right',wraplength=1080,font=('Segoe UI',12,'bold'),text_color=M3['ink'])
        summary_label.pack(fill='x',padx=34,pady=6)
        frame=ctk.CTkFrame(win,fg_color='white',corner_radius=16);frame.pack(fill='both',expand=True,padx=26,pady=(0,12))
        tree=ttk.Treeview(frame,show='headings');tree.pack(side='left',fill='both',expand=True)
        scroll=ttk.Scrollbar(frame,orient='vertical',command=tree.yview);scroll.pack(side='right',fill='y');tree.configure(yscrollcommand=scroll.set)
        current={'headers':[],'rows':[],'summary':[],'title':''}
        def display():
            try:
                kind=kinds[type_var.get()]
                vals={k:v.get().strip() for k,v in fields.items()}
                headers,rows,totals=build_v81_report(kind,**vals)
                if kind=='stock':
                    summary=[f'{wh} | {prod} | {weight}: صافي الحركة {format_money(qty)}' for (wh,prod,weight),qty in sorted(totals.items())]
                    # Keep every item in the exported summary; the on-screen label shows only five.
                else:
                    summary=[f'{cur}: {format_money(total)}' for cur,total in sorted(totals.items())]
                current.update(headers=headers,rows=rows,summary=summary,title=type_var.get())
                tree.delete(*tree.get_children());tree['columns']=[str(i) for i in range(len(headers))]
                for i,h in enumerate(headers):
                    tree.heading(str(i),text=h);tree.column(str(i),width=145,anchor='center',stretch=True)
                for row in rows:tree.insert('', 'end',values=[str(v) if v is not None else '' for v in row])
                summary_label.configure(text=f'عدد السجلات: {len(rows)} | '+(' | '.join(summary[:5]) if summary else 'لا توجد إجماليات'))
            except Exception as exc: messagebox.showerror('خطأ التقرير',str(exc),parent=win)
        def export(ext):
            if not current['headers']:
                messagebox.showwarning('التقارير','اعرض التقرير أولاً',parent=win);return
            path=filedialog.asksaveasfilename(parent=win,defaultextension='.'+ext,
                filetypes=[('PDF','*.pdf')] if ext=='pdf' else [('Excel','*.xlsx')],
                initialfile='PMP_'+kinds[type_var.get()]+'_'+date.today().isoformat()+'.'+ext)
            if not path:return
            try:
                args=(path,current['title'],current['headers'],current['rows'],current['summary'])
                if ext=='pdf':export_v81_pdf(*args)
                else:export_v81_excel(*args)
                messagebox.showinfo('تم التصدير','تم حفظ التقرير بنجاح',parent=win)
            except Exception as exc:messagebox.showerror('فشل التصدير',str(exc),parent=win)
        W.primary_button(controls,'عرض التقرير',display,width=240).pack(side='right',padx=5)
        W.primary_button(controls,'تصدير PDF',lambda:export('pdf'),width=170,tone='soft').pack(side='right',padx=5)
        W.primary_button(controls,'تصدير Excel',lambda:export('xlsx'),width=170,tone='soft').pack(side='right',padx=5)
        display()

    def customers_dialog(self):
        win = self._tool_window('PMP Paints | العملاء', '1020x740')
        outer = ctk.CTkScrollableFrame(win, fg_color='transparent')
        outer.pack(fill='both', expand=True, padx=12, pady=12)
        ctk.CTkLabel(outer, text='المتبقي = صافي الفواتير الفعّالة ناقص الدفعات الفعّالة، لكل عملة على حدة.',
                     text_color='#36577B').pack(pady=(0, 8))
        bar = ctk.CTkFrame(outer, fg_color='white', corner_radius=18, border_width=1, border_color='#DCE9F7')
        bar.pack(fill='x', pady=6)
        search = RoundedEntry(bar, width=30, placeholder_text='بحث بالاسم أو الهاتف...')
        search.pack(side='right', padx=8, pady=10)
        show_inactive = tk.BooleanVar(value=False)
        ctk.CTkCheckBox(bar, text='إظهار الموقوفين', variable=show_inactive,
                        command=lambda: load()).pack(side='right', padx=10)
        frame = ctk.CTkFrame(outer, fg_color='white')
        frame.pack(fill='x', pady=6)
        columns = ('id', 'name', 'phone', 'address', 'status', 'balance')
        tree = ttk.Treeview(frame, columns=columns, show='headings', height=10, style='PMP.Treeview')
        for key, title, width in [('id', 'رقم', 55), ('name', 'العميل', 200), ('phone', 'الهاتف', 120),
                                  ('address', 'العنوان', 180), ('status', 'الحالة', 80), ('balance', 'المتبقي عليه', 260)]:
            tree.heading(key, text=title)
            tree.column(key, width=width, anchor='center', minwidth=50)
        tree.pack(side='left', fill='both', expand=True)
        ttk.Scrollbar(frame, orient='vertical', command=tree.yview).pack(side='right', fill='y')
        state = {'rows': {}}

        form = ctk.CTkFrame(outer, fg_color='white', corner_radius=14)
        form.pack(fill='x', pady=8)
        ctk.CTkLabel(form, text='بيانات العميل (اختر عميلاً من الجدول لتعديله، أو اكتب بيانات جديدة)',
                     font=('Segoe UI', 12, 'bold'), text_color=M3['ink'], anchor='e').pack(fill='x', padx=18, pady=(12, 4))
        grid = ctk.CTkFrame(form, fg_color='transparent')
        grid.pack(fill='x', padx=10, pady=4)
        entries = {}
        for idx, (key, label) in enumerate([('name', 'اسم العميل *'), ('phone', 'الهاتف'),
                                            ('address', 'العنوان'), ('notes', 'ملاحظات')]):
            cell = ctk.CTkFrame(grid, fg_color='transparent')
            cell.grid(row=idx // 2, column=1 - idx % 2, sticky='ew', padx=9, pady=6)
            grid.grid_columnconfigure(1 - idx % 2, weight=1)
            ctk.CTkLabel(cell, text=label, text_color=M3['ink'], anchor='e', font=('Segoe UI', 11, 'bold')).pack(fill='x')
            entries[key] = RoundedEntry(cell, width=30)
            entries[key].pack(fill='x')

        def form_data():
            return {key: widget.get() for key, widget in entries.items()}
        def clear_form():
            for widget in entries.values():
                widget.delete(0, 'end')
            tree.selection_remove(tree.selection())
        def selected_id():
            chosen = tree.selection()
            return int(tree.item(chosen[0], 'values')[0]) if chosen else None
        def load():
            for iid in tree.get_children():
                tree.delete(iid)
            q = search.get().strip().lower()
            balances = customer_balances()
            with connection() as con:
                rows = con.execute('SELECT id,name,phone,address,notes,active FROM customers ORDER BY name').fetchall()
            state['rows'] = {r[0]: r for r in rows}
            for cid, name, phone, address, notes, active in rows:
                if not active and not show_inactive.get():
                    continue
                if q and q not in name.lower() and q not in phone.lower():
                    continue
                owed = self._money_by_currency((cur, b['balance']) for (c, cur), b in balances.items() if c == cid)
                tree.insert('', 'end', values=(cid, name, phone, address, 'فعّال' if active else 'موقوف', owed))
        def on_select(_event=None):
            cid = selected_id()
            row = state['rows'].get(cid)
            if not row:
                return
            for key, value in zip(('name', 'phone', 'address', 'notes'), row[1:5]):
                entries[key].delete(0, 'end')
                entries[key].insert(0, value)
        def guarded(action):
            try:
                action()
            except (ValueError, PermissionError, sqlite3.Error) as exc:
                messagebox.showerror('تعذر التنفيذ', str(exc), parent=win)
        def add_customer():
            create_customer(form_data(), self.user['username'])
            clear_form(); load()
            messagebox.showinfo('تم', 'تمت إضافة العميل', parent=win)
        def save_customer():
            cid = selected_id()
            if cid is None:
                raise ValueError('اختر عميلاً من الجدول أولاً')
            update_customer(cid, form_data(), self.user['username'])
            load()
            messagebox.showinfo('تم', 'تم حفظ التعديلات', parent=win)
        def toggle_active():
            cid = selected_id()
            if cid is None:
                raise ValueError('اختر عميلاً من الجدول أولاً')
            now_active = bool(state['rows'][cid][5])
            if not messagebox.askyesno('تأكيد', 'إيقاف هذا العميل؟ لن تُصدر له فواتير جديدة.' if now_active else 'تفعيل هذا العميل؟', parent=win):
                return
            set_customer_active(cid, not now_active, self.user['username'], self.user['role'])
            load()
        def open_invoices():
            cid = selected_id()
            if cid is None:
                raise ValueError('اختر عميلاً من الجدول أولاً')
            self.invoices_dialog(customer_id=cid)
        def new_invoice():
            cid = selected_id()
            if cid is None:
                raise ValueError('اختر عميلاً من الجدول أولاً')
            if not state['rows'][cid][5]:
                raise ValueError('العميل موقوف؛ فعّله أولاً')
            self.new_invoice_dialog(customer_id=cid, on_saved=load)

        buttons = ctk.CTkFrame(form, fg_color='transparent')
        buttons.pack(fill='x', padx=14, pady=(6, 14))
        RoundedButton(buttons, text='إضافة كعميل جديد', width=150, command=lambda: guarded(add_customer)).pack(side='right', padx=4)
        RoundedButton(buttons, text='حفظ تعديلات المحدد', width=150, command=lambda: guarded(save_customer)).pack(side='right', padx=4)
        RoundedButton(buttons, text='مسح الحقول', width=110, fg_color='#E6EEF9', hover_color='#D2E2F6',
                      text_color=M3['ink'], command=clear_form).pack(side='right', padx=4)
        actions = ctk.CTkFrame(outer, fg_color='transparent')
        actions.pack(fill='x', pady=4)
        RoundedButton(actions, text='فواتير العميل المحدد', width=170, command=lambda: guarded(open_invoices)).pack(side='right', padx=4)
        RoundedButton(actions, text='فاتورة جديدة للعميل', width=170, command=lambda: guarded(new_invoice)).pack(side='right', padx=4)
        if self.user['role'] in ('manager', 'deputy'):
            RoundedButton(actions, text='إيقاف / تفعيل العميل', width=170, fg_color='#B42335', hover_color='#8B2936',
                          command=lambda: guarded(toggle_active)).pack(side='right', padx=4)
        tree.bind('<<TreeviewSelect>>', on_select)
        search.bind('<KeyRelease>', lambda _e: load())
        load()

    def invoices_dialog(self, customer_id=None):
        self._route('invoices_dialog', customer_id=customer_id)
        win = self._tool_window('PMP Paints | الفواتير', '1100x720')
        outer = ctk.CTkScrollableFrame(win, fg_color='transparent')
        outer.pack(fill='both', expand=True, padx=12, pady=12)
        scope = ctk.CTkLabel(outer, text='', text_color='#36577B')
        scope.pack(pady=(0, 6))
        if customer_id:
            with connection() as con:
                row = con.execute('SELECT name FROM customers WHERE id=?', (customer_id,)).fetchone()
            scope.configure(text=f'فواتير العميل: {row[0] if row else customer_id}')
        bar = ctk.CTkFrame(outer, fg_color='white', corner_radius=12)
        bar.pack(fill='x', pady=6)
        search = RoundedEntry(bar, width=26, placeholder_text='رقم الفاتورة أو اسم العميل أو الملاحظات...')
        search.pack(side='right', padx=8, pady=10)
        state_labels = {'كل الحالات': 'all', 'غير مسددة': 'unpaid', 'مسددة جزئياً': 'partial', 'مسددة': 'paid', 'ملغاة': 'cancelled'}
        state_var = tk.StringVar(value='كل الحالات')
        ttk.Combobox(bar, textvariable=state_var, values=list(state_labels), state='readonly', width=16,
                     font=('Segoe UI', 11), justify='right').pack(side='right', padx=8, ipady=3)
        frame = ctk.CTkFrame(outer, fg_color='white')
        frame.pack(fill='x', pady=6)
        columns = ('id', 'no', 'date', 'customer', 'currency', 'total', 'paid', 'balance', 'state')
        tree = ttk.Treeview(frame, columns=columns, show='headings', height=12, style='PMP.Treeview')
        for key, title, width in [('id', 'م', 45), ('no', 'رقم الفاتورة', 110), ('date', 'التاريخ', 95),
                                  ('customer', 'العميل', 190), ('currency', 'العملة', 60), ('total', 'الصافي', 110),
                                  ('paid', 'المدفوع', 110), ('balance', 'المتبقي', 110), ('state', 'الحالة', 100)]:
            tree.heading(key, text=title)
            tree.column(key, width=width, anchor='center', minwidth=40)
        tree.tag_configure('cancelled', foreground='#8A94A3')
        tree.tag_configure('paid', foreground='#08754B', background='#F0FBF5')
        tree.pack(side='left', fill='both', expand=True)
        ttk.Scrollbar(frame, orient='vertical', command=tree.yview).pack(side='right', fill='y')
        footer = ctk.CTkLabel(outer, text='', text_color=M3['ink'], font=('Segoe UI', 12, 'bold'), anchor='e')
        footer.pack(fill='x', padx=10, pady=4)
        shown = {'rows': []}
        def load():
            for iid in tree.get_children():
                tree.delete(iid)
            rows = list_invoices(search.get(), state_labels.get(state_var.get(), 'all'), customer_id)
            shown['rows'] = rows
            for inv_id, no, inv_date, cname, cur, total, paid, balance, st in rows:
                tree.insert('', 'end', iid=str(inv_id), tags=(st,),
                            values=(inv_id, no, inv_date, cname, cur, format_money(total), format_money(paid),
                                    format_money(balance), INVOICE_STATUS_LABELS[st]))
            footer.configure(text='المتبقي على الفواتير المعروضة: ' +
                             self._money_by_currency((r[4], r[7]) for r in rows) + f'   |   عدد الفواتير: {len(rows)}')
        def selected_invoice():
            chosen = tree.selection()
            if not chosen:
                messagebox.showwarning('تنبيه', 'حدد فاتورة من الجدول', parent=win)
                return None
            return int(chosen[0])
        def open_selected(_event=None):
            inv_id = selected_invoice()
            if inv_id:
                self.invoice_detail_dialog(inv_id, on_change=load)
        def export_csv():
            filename = filedialog.asksaveasfilename(parent=win, defaultextension='.csv', filetypes=[('CSV', '*.csv')],
                                                    initialfile=f'PMP_invoices_{date.today().isoformat()}.csv')
            if not filename:
                return
            try:
                with open(filename, 'w', encoding='utf-8-sig', newline='') as f:
                    writer = csv.writer(f)
                    writer.writerow(['رقم الفاتورة', 'التاريخ', 'العميل', 'العملة', 'الصافي', 'المدفوع', 'المتبقي', 'الحالة'])
                    for _id, no, inv_date, cname, cur, total, paid, balance, st in shown['rows']:
                        writer.writerow([no, inv_date, cname, cur, str(total), str(paid), str(balance), INVOICE_STATUS_LABELS[st]])
                messagebox.showinfo('تم', f'تم تصدير {len(shown["rows"])} فاتورة', parent=win)
            except OSError as exc:
                messagebox.showerror('تعذر التصدير', str(exc), parent=win)
        RoundedButton(bar, text='تطبيق', width=90, command=load).pack(side='right', padx=6)
        actions = ctk.CTkFrame(outer, fg_color='transparent')
        actions.pack(fill='x', pady=4)
        RoundedButton(actions, text='فاتورة جديدة', width=150,
                      command=lambda: self.new_invoice_dialog(customer_id=customer_id, on_saved=load)).pack(side='right', padx=4)
        RoundedButton(actions, text='فتح الفاتورة المحددة', width=170, command=open_selected).pack(side='right', padx=4)
        RoundedButton(actions, text='تصدير CSV', width=120, command=export_csv).pack(side='right', padx=4)
        tree.bind('<Double-1>', open_selected)
        search.bind('<KeyRelease>', lambda _e: load())
        state_var.trace_add('write', lambda *_a: load())
        load()

    def new_invoice_dialog(self, customer_id=None, on_saved=None):
        self._route('new_invoice_dialog', customer_id=customer_id)
        win = self._tool_window('PMP Paints | فاتورة جديدة', '960x820')
        outer = ctk.CTkScrollableFrame(win, fg_color='transparent')
        outer.pack(fill='both', expand=True, padx=12, pady=12)
        card = ctk.CTkFrame(outer, fg_color='white', corner_radius=14)
        card.pack(fill='x', pady=6)
        head = ctk.CTkFrame(card, fg_color='transparent')
        head.pack(fill='x', padx=12, pady=10)
        labels, mapping = self._customer_choices()
        customer_var = tk.StringVar()
        if customer_id:
            match = next((lab for lab, cid in mapping.items() if cid == customer_id), '')
            customer_var.set(match)
        def field(parent, label):
            cell = ctk.CTkFrame(parent, fg_color='transparent')
            cell.pack(side='right', padx=8)
            ctk.CTkLabel(cell, text=label, text_color=M3['ink'], font=('Segoe UI', 11, 'bold'), anchor='e').pack(fill='x')
            return cell
        cell = field(head, 'العميل *')
        customer_box = ttk.Combobox(cell, textvariable=customer_var, values=labels, state='readonly', width=34,
                                    font=('Segoe UI', 11), justify='right')
        customer_box.pack(ipady=4)
        def reload_customers():
            new_labels, new_map = self._customer_choices()
            mapping.clear(); mapping.update(new_map)
            customer_box.configure(values=new_labels)
        RoundedButton(head, text='＋ عميل', width=80, fg_color='#E6EEF9', hover_color='#D2E2F6', text_color=M3['ink'],
                      command=lambda: (self.customers_dialog(), None)).pack(side='right', padx=4, pady=(16, 0))
        RoundedButton(head, text='↻', width=36, fg_color='#E6EEF9', hover_color='#D2E2F6', text_color=M3['ink'],
                      command=reload_customers).pack(side='right', padx=2, pady=(16, 0))
        cell = field(head, 'التاريخ *')
        date_entry = RoundedEntry(cell, width=12)
        date_entry.insert(0, date.today().isoformat())
        date_entry.pack()
        cell = field(head, 'العملة *')
        currency_box = RoundedCombo(cell, values=list(CURRENCIES), state='readonly', width=9)
        currency_box.set(CURRENCIES[0])
        currency_box.pack()

        items = []
        itemcard = ctk.CTkFrame(outer, fg_color='white', corner_radius=14)
        itemcard.pack(fill='x', pady=6)
        ctk.CTkLabel(itemcard, text='بنود الفاتورة', font=('Segoe UI', 14, 'bold'), text_color=M3['ink'],
                     anchor='e').pack(fill='x', padx=18, pady=(12, 4))
        addrow = ctk.CTkFrame(itemcard, fg_color='transparent')
        addrow.pack(fill='x', padx=12, pady=4)
        # Selecting a catalog item links the invoice line to a physical warehouse.
        with connection() as con:
            catalog=con.execute('SELECT id,sku,name,sale_price,paint_color,package_weight FROM products WHERE active=1 ORDER BY name').fetchall()
            stores=con.execute('SELECT id,name FROM warehouses WHERE active=1 ORDER BY name').fetchall()
        product_map={f'{sku} | {name} | {color or "—"} | {weight or "—"}':(pid,f'{name} - {color} - {weight}'.strip(' -'),price) for pid,sku,name,price,color,weight in catalog}
        store_map={name:wid for wid,name in stores}
        choice_row=ctk.CTkFrame(itemcard,fg_color='transparent');choice_row.pack(fill='x',padx=12,pady=3)
        cell=field(choice_row,'صنف من المخزون (اختياري)')
        product_choice=ttk.Combobox(cell,values=['— بند حر —']+list(product_map),state='readonly',width=37,font=('Segoe UI',11),justify='right')
        product_choice.set('— بند حر —');product_choice.pack(ipady=4)
        cell=field(choice_row,'مخزن الصرف')
        store_choice=ttk.Combobox(cell,values=list(store_map),state='readonly',width=26,font=('Segoe UI',11),justify='right')
        store_choice.pack(ipady=4)
        def picked_product(_event=None):
            selected=product_map.get(product_choice.get())
            if selected:
                desc_entry.delete(0,'end');desc_entry.insert(0,selected[1])
                price_entry.delete(0,'end');price_entry.insert(0,str(selected[2]))
        availability_label = ctk.CTkLabel(choice_row, text='الرصيد المتاح: اختر الصنف والمخزن', text_color='#164B87', font=('Segoe UI', 11, 'bold'))
        availability_label.pack(side='right', padx=8)
        def refresh_available(_event=None):
            chosen=product_map.get(product_choice.get())
            warehouse=store_map.get(store_choice.get())
            if not chosen or not warehouse:
                availability_label.configure(text='الرصيد المتاح: اختر الصنف والمخزن')
                return
            with connection() as con:
                available=stock_balance(con,warehouse,chosen[0])
                minimum=con.execute('SELECT minimum_stock FROM products WHERE id=?',(chosen[0],)).fetchone()[0]
            try: low=available <= Decimal(str(minimum))
            except Exception: low=False
            availability_label.configure(text=f'الرصيد: {format_money(available)} كرتونة'+('  ⚠ أقل من الحد الأدنى' if low else ''),
                                         text_color='#B42335' if low else '#17684A')
        product_choice.bind('<<ComboboxSelected>>', lambda event: (picked_product(event),refresh_available()))
        store_choice.bind('<<ComboboxSelected>>',refresh_available)
        def apply_poster_price():
            chosen=product_map.get(product_choice.get())
            if not chosen:
                messagebox.showwarning('تنبيه','اختر صنفاً أولاً',parent=win);return
            with connection() as con:
                info=con.execute('SELECT package_weight,paint_color FROM products WHERE id=?',(chosen[0],)).fetchone()
            weight=str(info[0] or '').strip().replace('جم','').replace('جرام','').strip()
            color=str(info[1] or '').strip()
            if color not in ('أبيض','ألوان','فضي','ذهبي','ورنيش'):
                color='ألوان' if color else ''
            if weight not in ('250','900') or not color:
                messagebox.showwarning('السعر المعتمد','الصنف لا يطابق أوزان وألوان بوستر الأسعار (250/900).',parent=win);return
            from price_poster import load_prices
            prices,_=load_prices(DB)
            price_entry.delete(0,'end');price_entry.insert(0,str(prices[(weight,color)]))
        RoundedButton(choice_row,text='جلب سعر البوستر',width=130,command=apply_poster_price).pack(side='right',padx=5,pady=(15,0))
        cell = field(addrow, 'البيان / الصنف')
        desc_entry = RoundedEntry(cell, width=34); desc_entry.pack()
        cell = field(addrow, 'الكمية')
        qty_entry = RoundedEntry(cell, width=10); qty_entry.insert(0, '1'); qty_entry.pack()
        cell = field(addrow, 'السعر / المبلغ')
        price_entry = RoundedEntry(cell, width=13); price_entry.pack()
        frame = ctk.CTkFrame(itemcard, fg_color='white')
        frame.pack(fill='x', padx=12, pady=6)
        tree = ttk.Treeview(frame, columns=('n', 'desc', 'qty', 'price', 'total'), show='headings', height=7, style='PMP.Treeview')
        for key, title, width in [('n', '#', 40), ('desc', 'البيان', 360), ('qty', 'الكمية', 80), ('price', 'سعر الوحدة', 110), ('total', 'الإجمالي', 120)]:
            tree.heading(key, text=title)
            tree.column(key, width=width, anchor='center', minwidth=35)
        tree.pack(side='left', fill='both', expand=True)
        ttk.Scrollbar(frame, orient='vertical', command=tree.yview).pack(side='right', fill='y')

        totals_label = ctk.CTkLabel(outer, text='', font=('Segoe UI', 13, 'bold'), text_color=M3['ink'], anchor='e')
        discount_entry = RoundedEntry(outer, width=16)
        discount_mode = tk.StringVar(value='مبلغ ثابت')
        notes_entry = RoundedEntry(outer, width=60, placeholder_text='ملاحظات (اختياري)')

        def line_total(item):
            return (Decimal(item['quantity']) * Decimal(item['unit_price'])).quantize(Decimal('0.01'), rounding=ROUND_HALF_UP)
        def recompute(*_):
            for iid in tree.get_children():
                tree.delete(iid)
            subtotal = Decimal('0')
            for n, item in enumerate(items, 1):
                lt = line_total(item)
                subtotal += lt
                tree.insert('', 'end', iid=str(n - 1), values=(n, item['description'], format_money(item['quantity']),
                                                                format_money(item['unit_price']), format_money(lt)))
            try:
                raw_discount = parse_money_2(discount_entry.get().strip() or '0', 'الخصم', allow_zero=True)
                discount = (subtotal * raw_discount / Decimal('100')).quantize(Decimal('0.01'), rounding=ROUND_HALF_UP) if discount_mode.get()=='نسبة %' else raw_discount
            except ValueError:
                discount = Decimal('0')
            net = subtotal - discount
            totals_label.configure(text=f'الإجمالي: {format_money(subtotal)}   |   الخصم: {format_money(discount)}   |   '
                                        f'الصافي: {format_money(net)} {currency_box.get()}')
        def add_item():
            try:
                desc = ' '.join(desc_entry.get().split())
                if not desc:
                    raise ValueError('اكتب بيان البند')
                qty = parse_quantity(qty_entry.get())
                price = parse_money_2(price_entry.get(), 'السعر / المبلغ', allow_zero=True)
            except ValueError as exc:
                messagebox.showerror('بند غير صحيح', str(exc), parent=win)
                return False
            entry={'description':desc,'quantity':str(qty),'unit_price':str(price)}
            selected=product_map.get(product_choice.get())
            if selected:
                if not store_map.get(store_choice.get()):
                    messagebox.showerror('مخزن مطلوب','اختر مخزن الصرف للصنف',parent=win)
                    return False
                warehouse_id=store_map[store_choice.get()]
                with connection() as con:
                    available=stock_balance(con,warehouse_id,selected[0])
                    product_info=con.execute('SELECT paint_color,minimum_stock FROM products WHERE id=?',(selected[0],)).fetchone()
                if product_info and product_info[0] and qty!=qty.to_integral_value():
                    messagebox.showerror('كمية غير صحيحة','البوهية تُباع بالكرتونة الكاملة فقط',parent=win);return False
                reserved=sum((Decimal(i['quantity']) for i in items if i.get('product_id')==selected[0] and i.get('warehouse_id')==warehouse_id),Decimal('0'))
                if qty+reserved>available:
                    messagebox.showerror('مخزون غير كافٍ',f'الرصيد المتاح {format_money(available)} كرتونة، والمطلوب مع بنود الفاتورة {format_money(qty+reserved)}',parent=win);return False
                entry.update(product_id=selected[0],warehouse_id=warehouse_id)
            items.append(entry)
            product_choice.set('— بند حر —')
            desc_entry.delete(0, 'end'); price_entry.delete(0, 'end')
            qty_entry.delete(0, 'end'); qty_entry.insert(0, '1')
            desc_entry.focus_set()
            recompute()
            return True
        def remove_item():
            chosen = tree.selection()
            if not chosen:
                messagebox.showwarning('تنبيه', 'حدد بنداً لحذفه', parent=win)
                return
            del items[int(chosen[0])]
            recompute()
        RoundedButton(addrow, text='＋ إضافة البند', width=120, command=add_item).pack(side='right', padx=8, pady=(16, 0))
        RoundedButton(itemcard, text='حذف البند المحدد', width=150, fg_color='#B42335', hover_color='#8B2936',
                      command=remove_item).pack(anchor='e', padx=18, pady=(0, 12))

        ctk.CTkLabel(outer, text='اكتب البيان والمبلغ ثم اضغط «إضافة البند» لكل صنف، أو اكتب بنداً واحداً واحفظ الفاتورة مباشرة (يُضاف تلقائياً).',
                     text_color='#36577B', font=('Segoe UI', 11), anchor='e').pack(fill='x', padx=14, pady=(0, 4))
        ctk.CTkLabel(outer, text='الخصم (اختياري)', text_color=M3['ink'], font=('Segoe UI', 11, 'bold'), anchor='e').pack(fill='x', padx=14)
        discount_mode_box = ttk.Combobox(outer,textvariable=discount_mode,values=['مبلغ ثابت','نسبة %'],state='readonly',width=15,justify='right')
        discount_mode_box.pack(anchor='e',padx=14,pady=(2,3))
        discount_mode_box.bind('<<ComboboxSelected>>',recompute)
        discount_entry.pack(anchor='e', padx=14, pady=(2, 8))
        notes_entry.pack(fill='x', padx=14, pady=4)
        totals_label.pack(fill='x', padx=14, pady=10)
        discount_entry.bind('<KeyRelease>', recompute)
        currency_box.configure(command=recompute)

        paycard = ctk.CTkFrame(outer, fg_color='white', corner_radius=14)
        paycard.pack(fill='x', pady=6)
        ctk.CTkLabel(paycard, text='السداد عند إصدار الفاتورة (اختياري)', font=('Segoe UI', 14, 'bold'),
                     text_color=M3['ink'], anchor='e').pack(fill='x', padx=18, pady=(12, 2))
        ctk.CTkLabel(paycard, text='لو كتبت مبلغاً هنا يُسجَّل تلقائياً كحركة «وارد» في الخزنة التي تختارها. اتركه فارغاً لفاتورة آجلة.',
                     text_color='#36577B', font=('Segoe UI', 11), anchor='e').pack(fill='x', padx=18, pady=(0, 6))
        payrow = ctk.CTkFrame(paycard, fg_color='transparent')
        payrow.pack(fill='x', padx=12, pady=(0, 12))
        cell = field(payrow, 'المبلغ المسدَّد الآن')
        pay_amount_entry = RoundedEntry(cell, width=14); pay_amount_entry.pack()
        cell = field(payrow, 'الخزنة التي يُسدَّد فيها')
        pay_cashbox_var = tk.StringVar(value='')
        ttk.Combobox(cell, textvariable=pay_cashbox_var, values=list(CASHBOXES), state='readonly', width=22,
                     font=('Segoe UI', 11), justify='right').pack(ipady=4)
        cell = field(payrow, 'تاريخ السداد')
        pay_date_entry = RoundedEntry(cell, width=12)
        pay_date_entry.insert(0, date.today().isoformat())
        pay_date_entry.pack()
        def fill_full_payment():
            try:
                sub = sum((line_total(i) for i in items), Decimal('0'))
                try:
                    disc = parse_money_2(discount_entry.get().strip() or '0', 'الخصم', allow_zero=True)
                    if discount_mode.get()=='نسبة %': disc=(sub*disc/Decimal('100')).quantize(Decimal('0.01'),rounding=ROUND_HALF_UP)
                except ValueError:
                    disc = Decimal('0')
                net = sub - disc
            except Exception:
                net = Decimal('0')
            pay_amount_entry.delete(0, 'end')
            if net > 0:
                pay_amount_entry.insert(0, str(net))
        RoundedButton(payrow, text='سداد الصافي كاملاً', width=140, fg_color='#E6EEF9', hover_color='#D2E2F6',
                      text_color=M3['ink'], command=fill_full_payment).pack(side='right', padx=8, pady=(16, 0))

        invoice_save_busy = [False]
        def save_invoice():
            # Prevent repeated clicks while the invoice and stock/cash movements are being posted.
            if invoice_save_busy[0]:
                return
            # A line typed but not yet added is added automatically.
            if desc_entry.get().strip() or price_entry.get().strip():
                if not add_item():
                    return
            try:
                cid = mapping.get(customer_var.get())
                if not cid:
                    raise ValueError('اختر العميل')
                payment = None
                if pay_amount_entry.get().strip():
                    if not pay_cashbox_var.get():
                        raise ValueError('اختر الخزنة التي سيُسدَّد فيها المبلغ')
                    payment = {'amount': pay_amount_entry.get(), 'cashbox': pay_cashbox_var.get(),
                               'date': pay_date_entry.get().strip(), 'note': 'سداد عند الإصدار'}
                raw_discount=parse_money_2(discount_entry.get().strip() or '0','الخصم',allow_zero=True)
                if discount_mode.get()=='نسبة %':
                    if raw_discount>100: raise ValueError('نسبة الخصم لا تتجاوز 100%')
                    subtotal_for_discount=sum((line_total(i) for i in items),Decimal('0'))
                    final_discount=(subtotal_for_discount*raw_discount/Decimal('100')).quantize(Decimal('0.01'),rounding=ROUND_HALF_UP)
                else: final_discount=raw_discount
                invoice_save_busy[0] = True
                invoice_save_button.configure(state='disabled', text='جاري حفظ الفاتورة...')
                try:
                    invoice_id, invoice_no, total = create_invoice(cid, date_entry.get().strip(), currency_box.get(), items,
                                                                   str(final_discount), notes_entry.get(), self.user['username'],
                                                                   payment=payment)
                except Exception:
                    invoice_save_busy[0] = False
                    invoice_save_button.configure(state='normal', text='▣   حفظ وإصدار الفاتورة')
                    raise
            except (ValueError, sqlite3.Error, PermissionError) as exc:
                messagebox.showerror('تعذر حفظ الفاتورة', str(exc), parent=win)
                return
            paid_text = (f'\nتم تسجيل سداد {format_money(Decimal(pay_amount_entry.get().strip().replace(",", "")))} '
                         f'{currency_box.get()} في «{pay_cashbox_var.get()}».') if payment else ''
            self.refresh()
            messagebox.showinfo('تم', f'تم إصدار الفاتورة {invoice_no} بصافي {format_money(total)} {currency_box.get()}{paid_text}', parent=win)
            win.destroy()
            if on_saved:
                on_saved()
            self.invoice_detail_dialog(invoice_id, on_change=on_saved)
        invoice_save_button = RoundedButton(outer, text='▣   حفظ وإصدار الفاتورة', height=44, command=save_invoice)
        invoice_save_button.pack(fill='x', padx=14, pady=(8, 16))
        recompute()

    def invoice_detail_dialog(self, invoice_id, on_change=None):
        self._route('invoice_detail_dialog', invoice_id)
        win = self._tool_window('PMP Paints | تفاصيل الفاتورة', '960x860')
        outer = ctk.CTkScrollableFrame(win, fg_color='transparent')
        outer.pack(fill='both', expand=True, padx=12, pady=12)
        title = ctk.CTkLabel(outer, text='', font=('Segoe UI', 20, 'bold'), text_color=M3['ink'])
        title.pack(pady=(4, 4))
        info = ctk.CTkLabel(outer, text='', text_color=M3['ink'], font=('Segoe UI', 12), justify='right', anchor='e')
        info.pack(fill='x', padx=14, pady=4)
        itemframe = ctk.CTkFrame(outer, fg_color='white')
        itemframe.pack(fill='x', padx=6, pady=6)
        items_tree = ttk.Treeview(itemframe, columns=('n', 'desc', 'qty', 'price', 'total'), show='headings', height=6, style='PMP.Treeview')
        for key, ttl, width in [('n', '#', 40), ('desc', 'البيان', 360), ('qty', 'الكمية', 80), ('price', 'سعر الوحدة', 110), ('total', 'الإجمالي', 120)]:
            items_tree.heading(key, text=ttl)
            items_tree.column(key, width=width, anchor='center', minwidth=35)
        items_tree.pack(fill='x')
        totals = ctk.CTkLabel(outer, text='', font=('Segoe UI', 13, 'bold'), text_color=M3['ink'], anchor='e', justify='right')
        totals.pack(fill='x', padx=14, pady=8)

        ctk.CTkLabel(outer, text='الدفعات', font=('Segoe UI', 14, 'bold'), text_color=M3['ink'], anchor='e').pack(fill='x', padx=14)
        payframe = ctk.CTkFrame(outer, fg_color='white')
        payframe.pack(fill='x', padx=6, pady=6)
        pay_tree = ttk.Treeview(payframe, columns=('id', 'date', 'cashbox', 'amount', 'status', 'note'), show='headings', height=5, style='PMP.Treeview')
        for key, ttl, width in [('id', 'م', 45), ('date', 'التاريخ', 100), ('cashbox', 'الخزنة', 150), ('amount', 'المبلغ', 120),
                                ('status', 'الحالة', 80), ('note', 'ملاحظة', 250)]:
            pay_tree.heading(key, text=ttl)
            pay_tree.column(key, width=width, anchor='center', minwidth=40)
        pay_tree.tag_configure('reversed', foreground='#8A94A3')
        pay_tree.pack(fill='x')

        payform = ctk.CTkFrame(outer, fg_color='white', corner_radius=14)
        payform.pack(fill='x', padx=6, pady=8)
        ctk.CTkLabel(payform, text='تسجيل دفعة من العميل (تُسجَّل كحركة «وارد» في الخزنة المختارة)', font=('Segoe UI', 12, 'bold'),
                     text_color=M3['ink'], anchor='e').pack(fill='x', padx=16, pady=(12, 4))
        row = ctk.CTkFrame(payform, fg_color='transparent')
        row.pack(fill='x', padx=10, pady=4)
        def pfield(label):
            cell = ctk.CTkFrame(row, fg_color='transparent')
            cell.pack(side='right', padx=7)
            ctk.CTkLabel(cell, text=label, text_color=M3['ink'], font=('Segoe UI', 11, 'bold'), anchor='e').pack(fill='x')
            return cell
        amount_entry = RoundedEntry(pfield('المبلغ'), width=13); amount_entry.pack()
        pay_date = RoundedEntry(pfield('التاريخ'), width=12); pay_date.pack()
        cashbox_var = tk.StringVar(value=CASHBOXES[0] if CASHBOXES else '')
        ttk.Combobox(pfield('الخزنة'), textvariable=cashbox_var, values=list(CASHBOXES), state='readonly', width=18,
                     font=('Segoe UI', 11), justify='right').pack(ipady=4)
        note_entry = RoundedEntry(pfield('ملاحظة'), width=20); note_entry.pack()
        pay_button = RoundedButton(payform, text='تسجيل الدفعة', width=170)
        pay_button.pack(anchor='e', padx=16, pady=(6, 14))

        actions = ctk.CTkFrame(outer, fg_color='transparent')
        actions.pack(fill='x', padx=6, pady=8)
        current = {'inv': None}

        def render():
            inv = get_invoice(invoice_id)
            current['inv'] = inv
            title.configure(text=f"فاتورة {inv['invoice_no']}  —  {INVOICE_STATUS_LABELS[inv['state']]}")
            lines = [f"العميل: {inv['customer']}" + (f"   |   الهاتف: {inv['phone']}" if inv['phone'] else ''),
                     f"التاريخ: {inv['invoice_date']}   |   العملة: {inv['currency']}   |   أصدرها: {inv['created_by']}"]
            if inv['address']:
                lines.append(f"العنوان: {inv['address']}")
            if inv['notes']:
                lines.append(f"ملاحظات: {inv['notes']}")
            if inv['status'] == 'cancelled':
                lines.append(f"سبب الإلغاء: {inv['cancel_reason']}")
            info.configure(text='\n'.join(lines))
            for iid in items_tree.get_children():
                items_tree.delete(iid)
            for n, it in enumerate(inv['items'], 1):
                items_tree.insert('', 'end', values=(n, it['description'], format_money(it['quantity']),
                                                      format_money(it['unit_price']), format_money(it['line_total'])))
            cur = inv['currency']
            totals.configure(text=f"الإجمالي: {format_money(inv['subtotal'])}   |   الخصم: {format_money(inv['discount'])}   |   "
                                  f"الصافي: {format_money(inv['total'])} {cur}\n"
                                  f"المدفوع: {format_money(inv['paid'])} {cur}   |   المتبقي: {format_money(inv['balance'])} {cur}")
            for iid in pay_tree.get_children():
                pay_tree.delete(iid)
            for p in inv['payments']:
                pay_tree.insert('', 'end', iid=str(p['id']), tags=(p['status'],),
                                values=(p['id'], p['pay_date'], p['cashbox'], f"{format_money(p['amount'])} {cur}",
                                        'فعّالة' if p['status'] == 'active' else 'معكوسة', p['note']))
            can_pay = inv['state'] in ('unpaid', 'partial')
            pay_button.configure(state='normal' if can_pay else 'disabled')
            amount_entry.delete(0, 'end')
            if can_pay:
                amount_entry.insert(0, str(inv['balance']))
            pay_date.delete(0, 'end')
            pay_date.insert(0, max(date.today().isoformat(), inv['invoice_date']))
            self.refresh()
            if on_change:
                on_change()

        def guarded(action):
            try:
                action()
            except (ValueError, PermissionError, sqlite3.Error) as exc:
                messagebox.showerror('تعذر التنفيذ', str(exc), parent=win)
        def do_pay():
            if not messagebox.askyesno('تأكيد الدفعة', f'تسجيل دفعة {amount_entry.get().strip()} {current["inv"]["currency"]} في «{cashbox_var.get()}»؟', parent=win):
                return
            record_invoice_payment(invoice_id, pay_date.get().strip(), cashbox_var.get(), amount_entry.get(), note_entry.get(), self.user['username'])
            note_entry.delete(0, 'end')
            render()
            messagebox.showinfo('تم', 'تم تسجيل الدفعة وتحديث رصيد الخزنة', parent=win)
        def do_reverse():
            chosen = pay_tree.selection()
            if not chosen:
                raise ValueError('حدد دفعة من جدول الدفعات')
            reason = self._ask_text(win, 'عكس دفعة', 'اكتب سبب عكس الدفعة (إجباري). سيُسجَّل قيد منصرف بتاريخ اليوم ولن تُحذف الحركة الأصلية.')
            if not reason:
                return
            reverse_invoice_payment(int(chosen[0]), reason, self.user['username'], self.user['role'])
            render()
            messagebox.showinfo('تم', 'تم عكس الدفعة', parent=win)
        def do_cancel():
            reason = self._ask_text(win, 'إلغاء الفاتورة', 'اكتب سبب إلغاء الفاتورة (إجباري). لا يمكن الإلغاء قبل عكس كل الدفعات.')
            if not reason:
                return
            cancel_invoice(invoice_id, reason, self.user['username'], self.user['role'])
            render()
            messagebox.showinfo('تم', 'تم إلغاء الفاتورة', parent=win)
        def do_pdf():
            try:
                path = filedialog.asksaveasfilename(parent=win, defaultextension='.pdf', filetypes=[('PDF Document', '*.pdf')],
                                                    initialfile=f"{current['inv']['invoice_no']}.pdf")
                if not path:
                    return
                export_invoice_pdf(invoice_id, path)
                messagebox.showinfo('تم التصدير', f'تم حفظ الفاتورة PDF:\n{path}', parent=win)
            except Exception as exc:
                messagebox.showerror('تعذر تصدير PDF', str(exc), parent=win)
        pay_button.configure(command=lambda: guarded(do_pay))
        RoundedButton(actions, text='طباعة / تصدير PDF', width=170, command=do_pdf).pack(side='right', padx=4)
        if self.user['role'] in ('manager', 'deputy'):
            RoundedButton(actions, text='عكس الدفعة المحددة', width=170, fg_color='#B42335', hover_color='#8B2936',
                          command=lambda: guarded(do_reverse)).pack(side='right', padx=4)
            RoundedButton(actions, text='إلغاء الفاتورة', width=150, fg_color='#B42335', hover_color='#8B2936',
                          command=lambda: guarded(do_cancel)).pack(side='right', padx=4)
        render()

    def cashbox_management_dialog(self):
        if self.user['role'] not in ('manager','deputy'):
            messagebox.showerror('الصلاحيات','إدارة الخزن للمدير ونائب المدير فقط.',parent=self.root)
            return
        popup=self._embedded_screen('cashbox_management_dialog')
        popup.title('PMP Paints | إدارة الخزن')
        popup.geometry('610x510')
        popup.transient(self.root)
        popup.grab_set()
        import pmp_widgets as W
        outer=ctk.CTkScrollableFrame(popup,fg_color='transparent')
        outer.pack(fill='both',expand=True,padx=26,pady=(0,12))
        card=ctk.CTkFrame(outer,fg_color='white',corner_radius=16)
        card.pack(fill='x',pady=8)
        ctk.CTkLabel(card,text='إدارة الخزن',font=('Segoe UI',22,'bold'),text_color=M3['ink'],
                     anchor='e').pack(fill='x',padx=40,pady=(22,4))
        grid=W.FormGrid(card,columns=3)
        grid.pack(fill='x',padx=26,pady=(0,6))
        selected=tk.StringVar(value=CASHBOXES[0] if CASHBOXES else '')
        def build_selector(parent):
            w=RoundedCombo(parent,variable=selected,values=list(CASHBOXES),state='readonly',width=24)
            w.configure(height=42)
            return w
        selector=grid.add('الخزنة الحالية',build_selector)
        entry=grid.add('الاسم الجديد (للإضافة أو التعديل)',lambda c:W.field_entry(c,'اكتب اسم الخزنة'),span=2)
        def refresh_boxes(preferred=None):
            selector.configure(values=list(CASHBOXES))
            selected.set(preferred if preferred in CASHBOXES else (CASHBOXES[0] if CASHBOXES else ''))
        def save(op):
            try:
                old=selected.get() if op in ('rename','delete') else None
                if op in ('rename','delete') and old not in CASHBOXES:
                    raise ValueError('اختر خزنة صحيحة من القائمة.')
                if op=='delete' and not messagebox.askyesno('تأكيد الحذف',
                        f'هل تريد حذف الخزنة «{old}»؟\nيسمح بالحذف فقط إذا لم ترتبط بأي سجلات مالية.',parent=popup):
                    return
                new_name=entry.get().strip()
                manage_cashbox(op,new_name,self.user['username'],old)
                refresh_boxes(new_name if op!='delete' else None)
                entry.delete(0,'end')
                messagebox.showinfo('تم','تم حفظ التغيير وتسجيله. أعد تشغيل البرنامج لتحديث جميع القوائم.',parent=popup)
            except Exception as exc:
                messagebox.showerror('تعذر الحفظ',str(exc),parent=popup)
        actions=ctk.CTkFrame(card,fg_color='transparent')
        actions.pack(pady=(8,10))
        W.primary_button(actions,'إضافة خزنة جديدة',lambda:save('add'),width=240).pack(side='right',padx=6)
        W.primary_button(actions,'تعديل الخزنة المحددة',lambda:save('rename'),width=240,tone='soft').pack(side='right',padx=6)
        W.primary_button(actions,'حذف الخزنة المحددة',lambda:save('delete'),width=240,tone='danger').pack(side='right',padx=6)
        W.banner(card,'لا يمكن حذف خزنة مرتبطة بحركات أو تحويلات أو إقفالات حفاظاً على البيانات.',tone='warn').pack(
            fill='x',padx=40,pady=(4,22))

    def _export_financial_summary(self, rows):
        # Movement totals follow the selected filters; current balances are live per selected box/currency.
        totals={}
        for row in rows:
            key=(row[3],row[4])
            amounts=totals.setdefault(key,[Decimal('0'),Decimal('0')])
            amounts[0 if row[5]=='وارد' else 1]+=Decimal(str(row[6]))
        with connection() as con:
            query='SELECT cashbox,currency,direction,amount FROM movements WHERE 1=1'
            args=[]
            if self.filter_cashbox.get()!='كل الخزن':
                query+=' AND cashbox=?';args.append(self.filter_cashbox.get())
            if self.filter_currency.get()!='كل العملات':
                query+=' AND currency=?';args.append(self.filter_currency.get())
            balances={}
            for box,cur,direction,amount in con.execute(query,args):
                key=(box,cur)
                balances[key]=balances.get(key,Decimal('0'))+(Decimal(str(amount)) if direction=='وارد' else -Decimal(str(amount)))
        keys=sorted(set(totals)|set(balances))
        return [(box,cur,*totals.get((box,cur),[Decimal('0'),Decimal('0')]),balances.get((box,cur),Decimal('0'))) for box,cur in keys]

    def _movement_export_context(self):
        rows = self._filtered_movement_rows()
        if not rows:
            messagebox.showwarning('التصدير', 'لا توجد حركات مطابقة للبحث والفلاتر.', parent=self.root)
            return None
        filters = f"الخزنة: {self.filter_cashbox.get()} | العملة: {self.filter_currency.get()} | النوع: {self.filter_direction.get()} | البحث: {self.search_text.get().strip() or 'الكل'}"
        return rows, filters

    def export_movements_excel(self):
        context = self._movement_export_context()
        if context is None: return
        try:
            from openpyxl import Workbook
            from openpyxl.styles import Font, PatternFill, Alignment
            from openpyxl.utils import get_column_letter
            rows, filters = context
            path = filedialog.asksaveasfilename(parent=self.root, defaultextension='.xlsx',
                      filetypes=[('Excel Workbook', '*.xlsx')],
                      initialfile=f'PMP_movements_{date.today().isoformat()}.xlsx')
            if not path: return
            wb = Workbook(); ws = wb.active; ws.title = 'الحركات المسجلة'; ws.sheet_view.rightToLeft = True
            headers = ['رقم الحركة', 'رقم المستند', 'التاريخ', 'الخزنة', 'العملة', 'النوع', 'المبلغ', 'البيان']
            ws.merge_cells('A1:H1'); ws['A1'] = 'PMP Paints | الحركات المسجلة'
            ws['A1'].font = Font(size=17, bold=True, color='FFFFFF')
            ws['A1'].fill = PatternFill('solid', fgColor='123B66')
            ws.merge_cells('A2:H2'); ws['A2'] = filters
            ws.merge_cells('A3:H3'); ws['A3'] = f'تاريخ التصدير: {datetime.now():%Y-%m-%d %H:%M} | عدد الحركات: {len(rows)}'
            for col, head in enumerate(headers, 1):
                cell = ws.cell(5, col, head)
                cell.font = Font(bold=True, color='FFFFFF')
                cell.fill = PatternFill('solid', fgColor='2465A0')
                cell.alignment = Alignment(horizontal='center')
            for i, row in enumerate(rows, 6):
                for j, value in enumerate(row, 1):
                    cell = ws.cell(i, j, value)
                    cell.alignment = Alignment(horizontal='right' if j in (2,4,6,8) else 'center')
                    if i % 2 == 0: cell.fill = PatternFill('solid', fgColor='EFF5FC')
                ws.cell(i, 7).number_format = '#,##0.################'
            for j, width in enumerate([14, 20, 17, 23, 12, 15, 20, 55], 1):
                ws.column_dimensions[get_column_letter(j)].width = width
            ws.freeze_panes = 'A6'; ws.auto_filter.ref = f'A5:H{5+len(rows)}'
            summary=self._export_financial_summary(rows)
            start=7+len(rows)
            ws.merge_cells(start_row=start,start_column=1,end_row=start,end_column=8)
            ws.cell(start,1,'ملخص مالي — الإجماليات للحركات المفلترة؛ الرصيد الحالي لجميع الحركات في الخزنة والعملة')
            ws.cell(start,1).font=Font(bold=True,color='123B66',size=12)
            for col,title in enumerate(['الخزنة','العملة','إجمالي الوارد','إجمالي المنصرف','الرصيد الحالي'],1):
                c=ws.cell(start+1,col,title)
                c.font=Font(bold=True,color='FFFFFF')
                c.fill=PatternFill('solid',fgColor='2465A0')
            for n,(box,cur,incoming,outgoing,balance) in enumerate(summary,start+2):
                for col,value in enumerate([box,cur,float(incoming),float(outgoing),float(balance)],1):
                    c=ws.cell(n,col,value)
                    if col>=3:c.number_format='#,##0.00'

            wb.save(path)
            messagebox.showinfo('تم التصدير', f'تم تصدير {len(rows)} حركة إلى Excel.\n{path}', parent=self.root)
        except Exception as exc:
            messagebox.showerror('تعذر تصدير Excel', str(exc), parent=self.root)

    def export_movements_pdf(self):
        context = self._movement_export_context()
        if context is None: return
        try:
            from reportlab.platypus import SimpleDocTemplate, Table, TableStyle, Paragraph, Spacer, KeepTogether
            from reportlab.lib import colors
            from reportlab.lib.pagesizes import A4, landscape
            from reportlab.lib.styles import ParagraphStyle
            from reportlab.pdfbase import pdfmetrics
            from reportlab.pdfbase.ttfonts import TTFont
            import arabic_reshaper
            from bidi.algorithm import get_display
            rows, filters = context
            path = filedialog.asksaveasfilename(parent=self.root, defaultextension='.pdf',
                      filetypes=[('PDF Document', '*.pdf')],
                      initialfile=f'PMP_movements_{date.today().isoformat()}.pdf')
            if not path: return
            fontfile = next((str(p) for p in [Path('C:/Windows/Fonts/arial.ttf'),
                Path('C:/Windows/Fonts/tahoma.ttf'), Path('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf')]
                if p.exists()), None)
            if not fontfile:
                raise RuntimeError('تعذر العثور على خط عربي مناسب لإنشاء PDF.')
            pdfmetrics.registerFont(TTFont('PMPArabic', fontfile))
            def rtl(v): return get_display(arabic_reshaper.reshape(str(v)))
            style = ParagraphStyle('pmp', fontName='PMPArabic', fontSize=8, leading=12, alignment=1)
            def cell(v): return Paragraph(rtl(v), style)
            doc = SimpleDocTemplate(path, pagesize=landscape(A4), rightMargin=22,
                       leftMargin=22, topMargin=32, bottomMargin=32)
            story = [Paragraph(rtl('PMP Paints | تقرير الحركات المسجلة'),
                        ParagraphStyle('title', parent=style, fontSize=17, leading=25)),
                     Spacer(1, 8), Paragraph(rtl(filters), style),
                     Paragraph(rtl(f'تاريخ التصدير: {datetime.now():%Y-%m-%d %H:%M} | عدد الحركات: {len(rows)}'), style),
                     Spacer(1, 12)]
            headers = ['البيان', 'المبلغ', 'النوع', 'العملة', 'الخزنة', 'التاريخ', 'المستند', 'رقم']
            data = [[cell(x) for x in headers]]
            for row in rows:
                values = [row[7], format_money(row[6]), row[5], row[4], row[3], row[2], row[1], row[0]]
                data.append([cell(v) for v in values])
            table = Table(data, colWidths=[190, 92, 62, 48, 103, 78, 106, 45], repeatRows=1)
            table.setStyle(TableStyle([
                ('BACKGROUND', (0,0),(-1,0),colors.HexColor('#2465A0')),
                ('TEXTCOLOR',(0,0),(-1,0),colors.white),
                ('ROWBACKGROUNDS',(0,1),(-1,-1),[colors.white,colors.HexColor('#EDF4FB')]),
                ('GRID',(0,0),(-1,-1),0.25,colors.HexColor('#D6E1EC')),
                ('VALIGN',(0,0),(-1,-1),'MIDDLE'),
                ('TOPPADDING',(0,0),(-1,-1),6),('BOTTOMPADDING',(0,0),(-1,-1),6),
            ]))
            story.append(table)
            story.append(Spacer(1,15))
            story.append(Paragraph(rtl('ملخص مالي: الوارد والمنصرف للحركات المفلترة، والرصيد الحالي لكل خزنة وعملة دون فلتر البحث'),style))
            summary_data=[[cell(v) for v in ['الخزنة','العملة','إجمالي الوارد','إجمالي المنصرف','الرصيد الحالي']]]
            for box,cur,incoming,outgoing,balance in self._export_financial_summary(rows):
                summary_data.append([cell(v) for v in [box,cur,format_money(incoming),format_money(outgoing),format_money(balance)]])
            summary_table=Table(summary_data,colWidths=[165,80,145,145,145],repeatRows=1)
            summary_table.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),colors.HexColor('#2465A0')),
                ('GRID',(0,0),(-1,-1),0.3,colors.HexColor('#D6E1EC')),
                ('VALIGN',(0,0),(-1,-1),'MIDDLE')]))
            story.append(summary_table)
            def page_number(canvas, document):
                canvas.setFont('PMPArabic', 9)
                canvas.drawCentredString(landscape(A4)[0]/2, 16, f'PMP Paints  |  {document.page}')
            doc.build(story, onFirstPage=page_number, onLaterPages=page_number)
            messagebox.showinfo('تم التصدير', f'تم تصدير {len(rows)} حركة إلى PDF.\n{path}', parent=self.root)
        except Exception as exc:
            messagebox.showerror('تعذر تصدير PDF', str(exc), parent=self.root)

    def clear_fields(self):
        for key in self.required_fields:
            self._clear_field_error(key)
        for key in ('amount','description','reference'):
            self.fields[key].delete(0,'end')

    def database_health_dialog(self):
        """Read-only health check; never changes financial or inventory records."""
        if self.user.get('role') != 'manager':
            messagebox.showerror('الصلاحيات', 'هذه الشاشة للمدير فقط')
            return
        import pmp_widgets as W
        self._route('database_health_dialog')
        popup = self._tool_window('PMP | فحص سلامة البيانات', '790x600')
        body = ctk.CTkScrollableFrame(popup, fg_color='transparent')
        body.pack(fill='both', expand=True, padx=14, pady=(0, 10))
        grid = W.FormGrid(body, columns=3)
        grid.pack(fill='x', padx=10, pady=(6, 0))
        def value_label(cell):
            return ctk.CTkLabel(cell, text='—', anchor='e', font=('Segoe UI', 16, 'bold'), text_color=M3['muted'])
        audit_value = grid.add('سجلات التدقيق', value_label)
        fk_value = grid.add('أخطاء العلاقات', value_label)
        sqlite_value = grid.add('حالة SQLite', value_label)
        holder = ctk.CTkFrame(body, fg_color='transparent')
        holder.pack(fill='x', padx=24, pady=(10, 0))
        ctk.CTkLabel(body, text='تفاصيل الفحص', font=('Segoe UI', 14, 'bold'), text_color=M3['ink'],
                     anchor='e').pack(fill='x', padx=28, pady=(14, 4))
        result = ctk.CTkTextbox(body, height=170, font=('Segoe UI', 12), fg_color='#F7FAFE',
                                border_width=1, border_color=M3['outline'], corner_radius=12)
        result.pack(fill='x', padx=26, pady=(0, 8))
        def run_check():
            lines = []
            status = '—'
            violations = []
            audit_count = '—'
            try:
                with closing(connection()) as con:
                    status = str(con.execute('PRAGMA integrity_check').fetchone()[0])
                    lines.append('سلامة SQLite: ' + status)
                    violations = con.execute('PRAGMA foreign_key_check').fetchall()
                    lines.append('أخطاء العلاقات: ' + str(len(violations)))
                    tables = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
                    for table in ('users', 'audit_log', 'warehouses', 'products', 'stock_moves', 'movements', 'invoices'):
                        if table in tables:
                            n = con.execute('SELECT COUNT(*) FROM "' + table + '"').fetchone()[0]
                            lines.append(table + ' : ' + str(n))
                            if table == 'audit_log':
                                audit_count = str(n)
                    if 'products' in tables:
                        cols = {r[1] for r in con.execute('PRAGMA table_info(products)')}
                        if {'color', 'pack_size'} <= cols:
                            duplicates = con.execute('SELECT color,pack_size,COUNT(*) FROM products GROUP BY color,pack_size HAVING COUNT(*)>1 LIMIT 30').fetchall()
                            lines.append('أصناف بنفس اللون والوزن: ' + str(len(duplicates)) + ' (أول 30 مجموعة)')
                            lines.extend(str(x) for x in duplicates)
                lines.append('الفحص للقراءة فقط؛ لم يتم تغيير أي رصيد.')
            except Exception as exc:
                lines.append('تعذر إكمال الفحص: ' + str(exc))
                status = 'تعذر الفحص'
            healthy = status == 'ok' and not violations
            audit_value.configure(text=audit_count, text_color=M3['ink'])
            fk_value.configure(text=str(len(violations)), text_color=M3['green'] if not violations else '#B42318')
            sqlite_value.configure(text='سليم' if status == 'ok' else status,
                                   text_color=M3['green'] if status == 'ok' else '#B42318')
            for child in holder.winfo_children():
                child.destroy()
            banner = W.banner(holder, f'سلامة SQLite: {status}   |   أخطاء العلاقات: {len(violations)}   |   الفحص للقراءة فقط',
                              'success' if healthy else 'danger')
            banner.pack(fill='x')
            result.configure(state='normal'); result.delete('1.0', 'end')
            result.insert('1.0', '\n'.join(lines)); result.configure(state='disabled')
        W.primary_button(body, 'إعادة الفحص', run_check).pack(pady=(8, 16))
        run_check()

    def backup_database(self):
        if self.user['role'] not in ('manager', 'deputy'):
            messagebox.showerror('الصلاحيات', 'النسخ الاحتياطي والاسترداد للمدير ونائب المدير فقط')
            return
        popup = self._embedded_screen('backup_database')
        popup.title('PMP | النسخ الاحتياطي')
        popup.geometry('620x435')
        popup.minsize(540, 390)
        popup.transient(self.root)
        popup.configure(fg_color=M3['background'])
        popup.grab_set()
        import pmp_widgets as W
        panel = popup
        with closing(connection()) as con:
            last_backup = con.execute("SELECT MAX(created_at) FROM audit_log WHERE action='CREATE_BACKUP'").fetchone()[0]
        grid = W.FormGrid(panel, columns=3)
        grid.pack(fill='x', padx=26, pady=(8, 0))
        def text_value(text, color=M3['muted']):
            return lambda cell: ctk.CTkLabel(cell, text=text, anchor='e', font=('Segoe UI', 14, 'bold'), text_color=color)
        status = grid.add('حالة النسخ', text_value('لم يتم التحقق' if not last_backup else 'توجد نسخة سابقة'))
        grid.add('آخر نسخة احتياطية', text_value((last_backup or '—')[:16], M3['ink']))
        grid.add('مكان حفظ النسخ', text_value('يُختار المجلد عند إنشاء النسخة'))
        ctk.CTkLabel(panel, text='سيتم إنشاء نسخة مستقلة والتحقق من سلامتها قبل تأكيد النجاح. الاسترداد يستبدل البيانات الحالية بعد إنشاء نسخة أمان تلقائية.',
                     font=('Segoe UI', 12), text_color=M3['muted'], anchor='e', justify='right',
                     wraplength=760).pack(fill='x', padx=40, pady=(10, 6))
        def create_backup():
            target = filedialog.asksaveasfilename(parent=popup, title='حفظ نسخة احتياطية',
                         defaultextension='.db', filetypes=[('SQLite database', '*.db')],
                         initialfile='PMP_backup_'+datetime.now().strftime('%Y%m%d_%H%M%S')+'.db')
            if not target:
                return
            dest = Path(target).resolve()
            try:
                if dest == DB.resolve():
                    raise ValueError('لا يمكن استبدال قاعدة البيانات الأصلية')
                if dest.exists():
                    raise ValueError('الملف موجود بالفعل؛ اختر اسماً جديداً')
                with connection() as original:
                    with sqlite3.connect(dest) as backup:
                        original.backup(backup)
                        result = backup.execute('PRAGMA integrity_check').fetchone()[0]
                        if result != 'ok':
                            raise ValueError('فشل اختبار سلامة النسخة')
                with connection() as con:
                    con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
                                (self.user['username'], 'CREATE_BACKUP', f'نسخة احتياطية: {dest.name}'))
                status.configure(text=f'تم إنشاء نسخة سليمة: {dest.name}', text_color='#137547')
                messagebox.showinfo('نجاح', f'تم إنشاء نسخة احتياطية سليمة في:\n{dest}', parent=popup)
            except Exception as exc:
                status.configure(text='فشل إنشاء النسخة الاحتياطية', text_color='#B42318')
                messagebox.showerror('خطأ النسخ الاحتياطي', str(exc), parent=popup)
        def restore_database():
            source = filedialog.askopenfilename(parent=popup, title='اختيار نسخة PMP للاسترداد',
                         filetypes=[('SQLite database', '*.db'), ('All files', '*.*')])
            if not source:
                return
            try:
                validate_backup_database(source)
            except Exception as exc:
                messagebox.showerror('نسخة غير صالحة', str(exc), parent=popup)
                return
            if not messagebox.askyesno('تأكيد الاسترداد',
                 'سيتم استبدال جميع البيانات الحالية بمحتوى النسخة المختارة.\n'
                 'سيتم أولاً إنشاء نسخة أمان تلقائية من البيانات الحالية.\n'
                 'هل تريد المتابعة؟', parent=popup):
                return
            try:
                safety = restore_backup_safely(source, self.user['username'])
            except Exception as exc:
                messagebox.showerror('فشل الاسترداد', str(exc), parent=popup)
                return
            messagebox.showinfo('نجاح الاسترداد',
                'تم استرداد قاعدة البيانات بنجاح.\nنسخة الأمان السابقة: '
                + safety.name + '\nسيغلق البرنامج الآن. افتحه مجدداً لتسجيل الدخول.', parent=popup)
            self.root.destroy()

        actions = ctk.CTkFrame(panel, fg_color='transparent')
        actions.pack(pady=(26, 22))
        W.primary_button(actions, 'إنشاء نسخة احتياطية', create_backup, width=260).pack(side='right', padx=8)
        W.primary_button(actions, 'استرداد نسخة احتياطية', restore_database, width=260, tone='soft').pack(side='right', padx=8)

    def statement_dialog(self):
        popup = self._embedded_screen('statement_dialog')
        popup.title('PMP Paints | كشف حساب الخزنة')
        popup.geometry('1120x740')
        popup.minsize(750, 510)
        popup.configure(fg_color=M3['background'])
        popup.transient(self.root)

        scroll = ctk.CTkScrollableFrame(popup, fg_color='transparent', corner_radius=0)
        scroll.pack(fill='both', expand=True, padx=14, pady=12)
        filters = ctk.CTkFrame(scroll, fg_color='white', corner_radius=15,
                               border_width=1, border_color='#DCE5F0')
        filters.pack(fill='x', padx=5, pady=(0, 12))
        ctk.CTkLabel(filters, text='معايير كشف الحساب', font=('Segoe UI', 16, 'bold'),
                     text_color=M3['ink']).pack(anchor='e', padx=20, pady=(14, 7))
        inputs = ctk.CTkFrame(filters, fg_color='transparent')
        inputs.pack(fill='x', padx=15, pady=(0, 12))
        fields = {}
        definitions = [
            ('end', 'إلى تاريخ', date.today().isoformat(), None),
            ('start', 'من تاريخ', date.today().replace(day=1).isoformat(), None),
            ('currency', 'العملة', 'SDG', CURRENCIES),
            ('cashbox', 'الخزنة', CASHBOXES[0], CASHBOXES),
        ]
        for i, (key, label, default, options) in enumerate(definitions):
            inputs.grid_columnconfigure(i, weight=1, uniform='statement')
            cell = ctk.CTkFrame(inputs, fg_color='transparent')
            cell.grid(row=0, column=i, sticky='ew', padx=6, pady=4)
            ctk.CTkLabel(cell, text=label, anchor='e', font=('Segoe UI', 12, 'bold'),
                         text_color='#294562').pack(fill='x', pady=(0, 5))
            if options:
                widget = RoundedCombo(cell, values=list(options), state='readonly', width=19)
                widget.set(default)
            else:
                widget = RoundedEntry(cell, width=19)
                widget.insert(0, default)
            widget.pack(fill='x')
            fields[key] = widget

        action_row = ctk.CTkFrame(filters, fg_color='transparent')
        action_row.pack(fill='x', padx=20, pady=(0, 15))
        status = ctk.CTkLabel(action_row, text='حدد الخزنة والعملة والفترة ثم اعرض الكشف',
                              text_color='#61758D', font=('Segoe UI', 11), anchor='e')
        status.pack(side='right', fill='x', expand=True, padx=8)

        cards_frame = ctk.CTkFrame(scroll, fg_color='transparent')
        cards_frame.pack(fill='x', padx=5, pady=(0, 12))
        card_values = {}
        for i, (key, title, color) in enumerate([
            ('closing', 'الرصيد الختامي', '#174B9C'),
            ('outgoing', 'إجمالي المنصرف', '#C54751'),
            ('incoming', 'إجمالي الوارد', '#168363'),
            ('opening', 'الرصيد الافتتاحي', '#425E85'),
        ]):
            cards_frame.grid_columnconfigure(i, weight=1, uniform='balance')
            card = ctk.CTkFrame(cards_frame, fg_color='white', corner_radius=14,
                                border_width=1, border_color='#DCE5F0')
            card.grid(row=0, column=i, sticky='ew', padx=5)
            ctk.CTkLabel(card, text=title, font=('Segoe UI', 12, 'bold'),
                         text_color='#526981').pack(anchor='e', padx=14, pady=(12, 4))
            value_label = ctk.CTkLabel(card, text='—', font=('Segoe UI', 19, 'bold'),
                                       text_color=color, anchor='e')
            value_label.pack(fill='x', padx=14, pady=(0, 13))
            card_values[key] = value_label

        table_card = ctk.CTkFrame(scroll, fg_color='white', corner_radius=15,
                                  border_width=1, border_color='#DCE5F0')
        table_card.pack(fill='both', expand=True, padx=5, pady=(0, 12))
        ctk.CTkLabel(table_card, text='حركات الخزنة خلال الفترة', font=('Segoe UI', 16, 'bold'),
                     text_color=M3['ink']).pack(anchor='e', padx=20, pady=(14, 8))
        table_frame = ctk.CTkFrame(table_card, fg_color='white')
        table_frame.pack(fill='both', expand=True, padx=15, pady=(0, 15))
        style = ttk.Style(popup)
        style.configure('Statement.Treeview', rowheight=32, font=('Segoe UI', 10),
                        background='#FFFFFF', fieldbackground='#FFFFFF', foreground='#17324D')
        style.configure('Statement.Treeview.Heading', font=('Segoe UI', 10, 'bold'),
                        background='#E7EFFA', foreground='#102B50')
        cols = ('date', 'reference', 'description', 'incoming', 'outgoing', 'balance')
        table = ttk.Treeview(table_frame, columns=cols, show='headings',
                             style='Statement.Treeview', height=12)
        for key, label in zip(cols, ['التاريخ', 'رقم المستند', 'البيان', 'الوارد', 'المنصرف', 'الرصيد الجاري']):
            table.heading(key, text=label)
            table.column(key, width=155 if key != 'description' else 240,
                         minwidth=95, anchor='center', stretch=True)
        vertical = ttk.Scrollbar(table_frame, orient='vertical', command=table.yview)
        horizontal = ttk.Scrollbar(table_frame, orient='horizontal', command=table.xview)
        table.configure(yscrollcommand=vertical.set, xscrollcommand=horizontal.set)
        table_frame.grid_columnconfigure(0, weight=1)
        table_frame.grid_rowconfigure(0, weight=1)
        table.grid(row=0, column=0, sticky='nsew')
        vertical.grid(row=0, column=1, sticky='ns')
        horizontal.grid(row=1, column=0, sticky='ew')

        export_rows = []
        def render():
            nonlocal export_rows
            try:
                box = fields['cashbox'].get()
                currency = fields['currency'].get()
                start = fields['start'].get().strip()
                end = fields['end'].get().strip()
                if box not in CASHBOXES or currency not in CURRENCIES:
                    raise ValueError('خزنة أو عملة غير صحيحة')
                date.fromisoformat(start)
                date.fromisoformat(end)
                if start > end:
                    raise ValueError('تاريخ البداية بعد تاريخ النهاية')
                with connection() as con:
                    prior = con.execute('SELECT direction,amount FROM movements WHERE cashbox=? AND currency=? AND tx_date<?',
                                        (box, currency, start)).fetchall()
                    movements = con.execute('''SELECT m.tx_date,COALESCE(m.reference,'—'),m.description,m.direction,m.amount,
                        m.id,mc.request_id,mc.action,mc.replacement_movement_id,
                        (SELECT original.id FROM movement_changes linked
                         JOIN movements original ON original.id=linked.movement_id
                         WHERE linked.replacement_movement_id=m.id LIMIT 1)
                        FROM movements m LEFT JOIN movement_changes mc ON mc.movement_id=m.id
                        WHERE m.cashbox=? AND m.currency=? AND m.tx_date>=? AND m.tx_date<=?
                        ORDER BY m.tx_date,m.id''',(box,currency,start,end)).fetchall()
                def cents(value):
                    return int((Decimal(str(value))*100).quantize(Decimal('1'), rounding=ROUND_HALF_UP))
                balance = sum((1 if d == 'وارد' else -1)*cents(a) for d, a in prior)
                opening = balance
                incoming = outgoing = 0
                for item in table.get_children():
                    table.delete(item)
                export_rows = [['التاريخ', 'رقم المستند', 'البيان', 'الوارد', 'المنصرف', 'الرصيد الجاري'],
                               ['', '', 'رصيد افتتاحي', '', '', format_money(Decimal(opening)/100)]]
                table.insert('', 'end', values=('', '', 'رصيد افتتاحي', '', '', format_money(Decimal(opening)/100)))
                table.tag_configure('original_cancelled',background='#FFF2E6')
                table.tag_configure('reversal',background='#E9F7EF')
                for dt, ref, description, direction, amount, movement_id, request_id, action, replacement_id, original_id in movements:
                    tags=()
                    if request_id is not None:
                        description=f'{description} | الأصل #{movement_id} / طلب #{request_id} ({"إلغاء" if action=="cancel" else "تصحيح"})'
                        tags=('original_cancelled',)
                    elif original_id is not None:
                        # A reversal is a separate posting and must remain visible.
                        description=f'{description} | قيد عكس مرتبط بالحركة #{original_id}'
                        tags=('reversal',)
                    value = cents(amount)
                    inc = value if direction == 'وارد' else 0
                    out = value if direction == 'منصرف' else 0
                    balance += inc - out
                    incoming += inc
                    outgoing += out
                    row = [dt, ref, description, format_money(Decimal(inc)/100) if inc else '',
                           format_money(Decimal(out)/100) if out else '', format_money(Decimal(balance)/100)]
                    export_rows.append(row)
                    table.insert('', 'end', values=[dt, ref, description,
                                 format_money(Decimal(inc)/100) if inc else '',
                                 format_money(Decimal(out)/100) if out else '', f'{format_money(Decimal(balance)/100)}'],tags=tags)
                for key, amount in [('opening', opening), ('incoming', incoming),
                                    ('outgoing', outgoing), ('closing', balance)]:
                    card_values[key].configure(text=f'{format_money(Decimal(amount)/100)} {currency}')
                status.configure(text=f'{box}  |  {currency}  |  عدد الحركات: {len(movements)}')
            except (ValueError, sqlite3.Error) as exc:
                messagebox.showerror('خطأ في الكشف', str(exc), parent=popup)

        def export_report(kind):
            if not export_rows:
                messagebox.showwarning('التصدير','اعرض الكشف أولاً',parent=popup);return
            ext='.xlsx' if kind=='excel' else '.pdf'
            filename=filedialog.asksaveasfilename(parent=popup,defaultextension=ext,
                filetypes=[('Excel','*.xlsx')] if kind=='excel' else [('PDF','*.pdf')],
                initialfile=f'PMP_statement_{date.today().isoformat()}{ext}')
            if not filename:return
            try:
                box=fields['cashbox'].get();currency=fields['currency'].get()
                subtitle=f'الخزنة: {box} | العملة: {currency} | من {fields["start"].get()} إلى {fields["end"].get()}'
                headers=export_rows[0];rows=export_rows[1:]
                if kind=='excel':_pmp_report_excel(filename,'PMP Paints | كشف حساب الخزنة',subtitle,headers,rows)
                else:_pmp_report_pdf(filename,'PMP Paints | كشف حساب الخزنة',subtitle,
                    list(reversed(headers)),[list(reversed(r)) for r in rows],widths=[135,100,100,185,110,110])
                messagebox.showinfo('تم',f'تم تصدير الكشف إلى:\n{filename}',parent=popup)
            except Exception as exc:messagebox.showerror('تعذر التصدير',str(exc),parent=popup)
        RoundedButton(action_row,text='تصدير Excel',command=lambda:export_report('excel'),
            fg_color='#168363',hover_color='#116C51',width=135).pack(side='left',padx=5)
        RoundedButton(action_row,text='تصدير PDF',command=lambda:export_report('pdf'),
            fg_color='#164B87',width=135).pack(side='left',padx=5)
        RoundedButton(action_row, text='عرض الكشف', command=render,
                      width=135).pack(side='left', padx=5)
        render()

    def transfer_dialog(self):
        if self.user['role'] not in ('manager', 'accountant', 'deputy'):
            return
        popup = self._embedded_screen('transfer_dialog')
        popup.title('PMP Paints | تحويل بين الخزن والعملات')
        popup.geometry('850x680')
        popup.minsize(620, 490)
        popup.configure(fg_color=M3['background'])
        popup.transient(self.root)
        popup.grab_set()

        import pmp_widgets as W
        scroll = ctk.CTkScrollableFrame(popup, fg_color='transparent', corner_radius=0)
        scroll.pack(fill='both', expand=True, padx=26, pady=(0, 10))
        card = ctk.CTkFrame(scroll, fg_color='#FFFFFF', corner_radius=16)
        card.pack(fill='x', pady=8)
        ctk.CTkLabel(card, text='التحويل بين الخزن', font=('Segoe UI', 22, 'bold'),
                     text_color=M3['ink'], anchor='e').pack(fill='x', padx=40, pady=(22, 4))
        content = W.FormGrid(card, columns=3)          # Figma 3-column form, first field on the right
        content.pack(fill='x', padx=26, pady=(0, 4))
        cashboxes = list(CASHBOXES)
        currencies = list(CURRENCIES)
        fields = {}

        def field(key, label, default, options=None):
            if options is not None:
                def build(parent):
                    w = RoundedCombo(parent, values=options, state='readonly', width=32)
                    w.configure(height=42)
                    w.set(default)
                    return w
                w = content.add(label, build)
            else:
                w = content.add(label, lambda parent: W.field_entry(parent, ''))
                if default:
                    w.insert(0, default)
            fields[key] = w
            return w

        field('source_cashbox', 'من خزنة', cashboxes[0], cashboxes)
        field('destination_cashbox', 'إلى خزنة', cashboxes[1], cashboxes)
        field('source_amount', 'المبلغ (مبلغ الخصم)', '')
        field('source_currency', 'عملة الخصم', 'SDG', currencies)
        field('destination_currency', 'عملة الإضافة', 'SDG', currencies)
        field('rate', 'سعر الصرف', '1')
        field('reference', 'المرجع (رقم التحويل)', '')
        field('date', 'تاريخ الحركة (YYYY-MM-DD)', date.today().isoformat())
        field('description', 'ملاحظات التحويل / سبب التحويل', '')
        try:
            import pmp_factory
            with connection() as _hcon:
                pmp_factory.init_halfa(_hcon)
                open_halfa = pmp_factory.halfa_invoice_rows(_hcon, only_open=True)
        except sqlite3.Error:
            open_halfa = []
        halfa_labels = {f'{r[1]} | متبقي {r[5]} {r[6]}': r[0] for r in open_halfa}
        field('halfa_invoice', 'سداد فاتورة حلفا (اختياري)', '—', ['—'] + list(halfa_labels))
        field('halfa_amount', 'المبلغ المخصوم من الفاتورة (فارغ = الأقصى)', '')
        rate_mode = ctk.StringVar(value='direct')
        mode_box = ctk.CTkFrame(card, fg_color='#EFF6FF', corner_radius=10)
        mode_box.pack(fill='x', padx=40, pady=(4, 6))
        rate_hint = ctk.CTkLabel(mode_box, text='', font=('Segoe UI', 12, 'bold'), text_color=M3['ink'])
        rate_hint.pack(anchor='e', padx=12, pady=(8, 2))
        rate_choice = ctk.CTkSegmentedButton(mode_box, values=['سعر مباشر', 'سعر عكسي'],
                                             command=lambda value: change_rate_mode(value))
        rate_choice.set('سعر مباشر')
        rate_choice.pack(fill='x', padx=12, pady=(2, 9))
        preview = ctk.CTkFrame(card, fg_color='#EFF6FF', corner_radius=11)
        preview.pack(fill='x', padx=40, pady=(0, 18))
        ctk.CTkLabel(preview, text='المبلغ المتوقع استلامه', font=('Segoe UI', 11),
                     text_color='#45637F').pack(pady=(8, 0))
        preview_value = ctk.CTkLabel(preview, text='—', font=('Segoe UI', 16, 'bold'),
                                     text_color='#135BC8')
        preview_value.pack(pady=(0, 8))

        def update_rate_hint():
            source = fields['source_currency'].get()
            destination = fields['destination_currency'].get()
            if rate_mode.get() == 'direct':
                rate_hint.configure(text=f'1 {source} = ? {destination}  (السعر المباشر)')
            else:
                rate_hint.configure(text=f'1 {destination} = ? {source}  (السعر العكسي)')

        def change_rate_mode(value):
            old_mode = rate_mode.get()
            new_mode = 'direct' if value == 'سعر مباشر' else 'inverse'
            if old_mode != new_mode:
                try:
                    previous = Decimal(fields['rate'].get().strip())
                    if previous.is_finite() and previous > 0:
                        fields['rate'].delete(0, 'end')
                        fields['rate'].insert(0, format(Decimal(1) / previous, '.10f').rstrip('0').rstrip('.'))
                except (InvalidOperation, ValueError, ZeroDivisionError):
                    pass
            rate_mode.set(new_mode)
            update_rate_hint()
            update_preview()

        def normalized_rate():
            raw = Decimal(fields['rate'].get().strip())
            if not raw.is_finite() or raw <= 0:
                raise ValueError('سعر الصرف يجب أن يكون أكبر من صفر')
            sc = fields['source_currency'].get()
            dc = fields['destination_currency'].get()
            if sc == dc:
                if raw != 1:
                    raise ValueError('التحويل بنفس العملة يتطلب سعر صرف 1')
                return Decimal(1)
            actual = raw if rate_mode.get() == 'direct' else Decimal(1) / raw
            actual = actual.quantize(Decimal('0.000001'), rounding=ROUND_HALF_UP)
            if actual <= 0:
                raise ValueError('سعر الصرف صغير جداً؛ لا يمكن تمثيله بدقة كافية')
            return actual

        def update_preview(*_):
            update_rate_hint()
            try:
                amount = Decimal(fields['source_amount'].get().strip())
                rate = normalized_rate()
                if not amount.is_finite() or amount <= 0:
                    raise ValueError()
                result = (amount * rate).quantize(Decimal('0.01'), rounding=ROUND_HALF_UP)
                preview_value.configure(text=f'{format_money(result)} {fields["destination_currency"].get()}')
            except (ValueError, InvalidOperation, ZeroDivisionError):
                preview_value.configure(text='—')

        for key in ('source_amount', 'rate'):
            fields[key].bind('<KeyRelease>', update_preview)
        for key in ('source_currency', 'destination_currency'):
            fields[key].configure(command=lambda _value: update_preview())
        update_rate_hint()

        ctk.CTkLabel(scroll, text='اختار السعر المباشر (1 من عملة الخصم يساوي كم من عملة الإضافة) أو السعر العكسي. '
                     'البرنامج يحسب المبلغ المستلم ويخزن السعر المباشر للحفاظ على توافق التحويلات القديمة. '
                     'التحويل بنفس العملة يتطلب السعر 1.',
                     font=('Segoe UI', 11), text_color='#45637F', wraplength=690,
                     justify='right').pack(fill='x', padx=28, pady=6)

        actions = ctk.CTkFrame(scroll, fg_color='transparent')
        actions.pack(fill='x', padx=30, pady=(4, 18))

        def commit():
            data = {key: widget.get().strip() for key, widget in fields.items()}
            data['halfa_invoice_id'] = halfa_labels.get(data.pop('halfa_invoice', '—'))
            try:
                effective_rate = normalized_rate()
            except (ValueError, InvalidOperation, ZeroDivisionError) as exc:
                messagebox.showerror('سعر صرف غير صالح', str(exc), parent=popup)
                return
            entered_rate = data['rate']
            data['rate'] = str(effective_rate)
            summary = (f"رقم التحويل: {data['reference']}\n"
                       f"من: {data['source_cashbox']} — {data['source_amount']} {data['source_currency']}\n"
                       f"إلى: {data['destination_cashbox']} — {data['destination_currency']}\n"
                       f"السعر المدخل: {entered_rate} ({'مباشر' if rate_mode.get() == 'direct' else 'عكسي'})\n"
                       f"السعر المستخدم في الحساب: {data['rate']} {data['destination_currency']} لكل 1 {data['source_currency']}\n\nهل تريد تنفيذ التحويل؟")
            if not messagebox.askyesno('تأكيد التحويل المالي', summary, parent=popup):
                return
            try:
                result = post_transfer(data, self.user['username'])
            except (ValueError, sqlite3.Error) as exc:
                messagebox.showerror('تعذر التحويل', str(exc), parent=popup)
                return
            self.refresh()
            messagebox.showinfo('✓ تم التحويل',
                                f"تم خصم {result[0]} {data['source_currency']} وإضافة {result[1]} {data['destination_currency']}",
                                parent=popup)
            popup.destroy()

        W.primary_button(actions, 'تأكيد وحفظ التحويل', commit, width=300).pack(side='right', padx=6)
        W.primary_button(actions, 'إلغاء', popup.destroy, width=160, tone='soft').pack(side='right', padx=6)

    def add_user(self):
        if self.user['role'] != 'manager':
            return
        popup = self._embedded_screen('add_user')
        popup.title('PMP | إدارة المستخدمين')
        popup.geometry('650x540')
        popup.minsize(520, 490)
        popup.transient(self.root)
        popup.configure(fg_color=M3['background'])
        popup.grab_set()
        page = ctk.CTkScrollableFrame(popup, fg_color='transparent')
        page.pack(fill='both', expand=True, padx=10, pady=10)
        card = ctk.CTkFrame(page, fg_color='white', corner_radius=16,
                            border_width=1, border_color='#DDE6F1')
        card.pack(fill='x', padx=12, pady=12)
        ctk.CTkLabel(card, text='إضافة مستخدم جديد', font=('Segoe UI', 19, 'bold'),
                     text_color=M3['ink'], anchor='e').pack(fill='x', padx=24, pady=(20, 8))
        ctk.CTkLabel(card, text='صلاحية إضافة المستخدمين متاحة للمدير فقط',
                     font=('Segoe UI', 11), text_color='#64748B', anchor='e').pack(fill='x', padx=24, pady=(0, 12))
        entries = {}
        for key, label in [('username', 'اسم المستخدم'), ('display_name', 'الاسم الظاهر'),
                           ('password', 'كلمة المرور (12 حرفاً على الأقل)'),
                           ('phone','رقم الهاتف'), ('email','البريد الإلكتروني الشخصي'),
                           ('address','عنوان السكن'),('birth_date','تاريخ الميلاد YYYY-MM-DD'),
                           ('job_title','المسمى الوظيفي')]:
            ctk.CTkLabel(card, text=label, font=('Segoe UI', 12, 'bold'),
                         text_color=M3['ink'], anchor='e').pack(fill='x', padx=24, pady=(9, 3))
            entry = RoundedEntry(card, width=40, show='*' if key == 'password' else '')
            entry.pack(fill='x', padx=24)
            entries[key] = entry
        ctk.CTkLabel(card, text='الصلاحية', font=('Segoe UI', 12, 'bold'),
                     text_color=M3['ink'], anchor='e').pack(fill='x', padx=24, pady=(12, 3))
        role = RoundedCombo(card, values=['manager', 'accountant', 'deputy'], state='readonly', width=35)
        role.set('deputy')
        role.pack(fill='x', padx=24)
        photo = {'path': ''}
        photo_label = ctk.CTkLabel(card, text='لم يتم اختيار صورة', text_color=M3['muted'])
        photo_label.pack(pady=5)
        def pick_photo():
            selected = filedialog.askopenfilename(parent=popup, title='اختيار صورة الموظف',
                         filetypes=[('صور', '*.png *.jpg *.jpeg *.webp')])
            if selected:
                photo['path'] = selected
                photo_label.configure(text=Path(selected).name)
        RoundedButton(card, text='اختيار صورة شخصية', command=pick_photo).pack(pady=5)
        def commit():
            username = entries['username'].get().strip()
            display = entries['display_name'].get().strip()
            pw = entries['password'].get()
            if not username or not display or len(pw) < 12:
                messagebox.showerror('خطأ', 'أدخل الاسم وكلمة مرور من 12 حرفاً على الأقل', parent=popup)
                return
            try:
                birth = entries['birth_date'].get().strip()
                if birth:
                    datetime.strptime(birth, '%Y-%m-%d')
                email = entries['email'].get().strip()
                if email and ('@' not in email or '.' not in email.split('@')[-1]):
                    raise ValueError('البريد الإلكتروني غير صحيح')
                saved_photo = ''
                if photo['path']:
                    photos_dir = APP_DIR/'user_photos'
                    photos_dir.mkdir(exist_ok=True)
                    extension = Path(photo['path']).suffix.lower()
                    if extension not in ('.png','.jpg','.jpeg','.webp'):
                        raise ValueError('نوع الصورة غير مدعوم')
                    destination = photos_dir / (secrets.token_hex(16) + extension)
                    shutil.copy2(photo['path'], destination)
                    saved_photo = str(destination.relative_to(Path(__file__).parent))
                with connection() as con:
                    con.execute('''INSERT INTO users(username,display_name,role,password_hash,
                        phone,email,photo_path,address,birth_date,job_title)
                        VALUES(?,?,?,?,?,?,?,?,?,?)''',
                        (username, display, role.get(), password_hash(pw),
                         entries['phone'].get().strip(),email,saved_photo,
                         entries['address'].get().strip(),birth,entries['job_title'].get().strip()))
                log_action(self.user['username'], 'CREATE_USER', f'المستخدم {username} / {role.get()}')
                messagebox.showinfo('تم', 'تمت إضافة المستخدم', parent=popup)
                popup.destroy()
            except sqlite3.IntegrityError:
                messagebox.showerror('خطأ', 'اسم المستخدم مستخدم بالفعل', parent=popup)
            except (ValueError, OSError) as exc:
                messagebox.showerror('خطأ', str(exc), parent=popup)
        buttons = ctk.CTkFrame(card, fg_color='transparent')
        buttons.pack(fill='x', padx=24, pady=22)
        RoundedButton(buttons, text='حفظ المستخدم', command=commit, width=160, height=43).pack(side='right', padx=5)
        RoundedButton(buttons, text='إلغاء', command=popup.destroy, width=100, height=43,
                      fg_color='#E5EDF7', hover_color='#D5E1F0', text_color=M3['ink']).pack(side='right', padx=5)

    def reset_password_dialog(self):
        if self.user['role'] != 'manager':
            return
        popup = self._embedded_screen('reset_password_dialog')
        popup.title('إعادة تعيين كلمة المرور')
        popup.configure(fg_color=M3['background'])
        card = ctk.CTkFrame(popup, fg_color='white', corner_radius=16)
        card.pack(fill='x', padx=25, pady=25)
        ctk.CTkLabel(card, text='إعادة تعيين كلمة مرور موظف',
                     font=('Segoe UI', 19, 'bold'), text_color=M3['ink']).pack(pady=16)
        with connection() as con:
            names = [r[0] for r in con.execute(
                'SELECT username FROM users WHERE active=1 AND username<>? ORDER BY username',
                (self.user['username'],))]
        if not names:
            ctk.CTkLabel(card, text='لا يوجد مستخدمون نشطون آخرون').pack(pady=15)
            return
        target = RoundedCombo(card, values=names, state='readonly', width=300)
        target.set(names[0]); target.pack(pady=12)
        def perform():
            name = target.get()
            if not messagebox.askyesno('تأكيد', 'إعادة تعيين كلمة مرور '+name+'؟', parent=popup):
                return
            try:
                temporary = reset_user_password(self.user['username'], name)
            except (ValueError, PermissionError) as exc:
                messagebox.showerror('خطأ', str(exc), parent=popup)
                return
            # Shown once, never persisted in plaintext or audit log.
            output = ctk.CTkToplevel(self.root)
            output.title('كلمة المرور المؤقتة')
            output.geometry('520x260')
            output.transient(self.root)
            output.grab_set()
            ctk.CTkLabel(output, text='انسخ كلمة المرور المؤقتة وسلّمها للموظف بأمان',
                         font=('Segoe UI', 14, 'bold')).pack(pady=18)
            field = RoundedEntry(output, width=390)
            field.pack(pady=8); field.insert(0, temporary)
            ctk.CTkLabel(output, text='سيُطلب تغييرها عند أول تسجيل دخول').pack(pady=8)
            RoundedButton(output, text='إغلاق', command=output.destroy).pack(pady=10)
        RoundedButton(card, text='إعادة تعيين كلمة المرور', command=perform).pack(pady=15)

    def connection_help(self):
        url=self.branch_config.get('server_health_url','')
        if not url:
            detail='عنوان فحص السيرفر غير مضبوط.\nافتح ملف pmp_branch_config.json واضبط server_health_url بعنوان HTTPS الصحيح المنتهي بـ /health.'
        else:
            detail='عنوان الفحص الحالي:\n'+url+'\n\nالأخضر يعني نجاح فحص صحة السيرفر فقط، وليس تسجيل دخول أو مزامنة.'
        messagebox.showinfo('حالة اتصال السيرفر',detail,parent=self.root)

    def _start_server_probe(self):
        """Check HTTP in a worker; ALL Tk operations stay on the UI thread."""
        from pmp_branch import probe_server
        url = self.branch_config.get('server_health_url', '')
        if not url:
            self.set_connection_indicator('gray')
            return
        if getattr(self, '_probe_in_flight', False):
            return
        self._probe_in_flight = True
        self.set_connection_indicator('yellow')
        results = queue.SimpleQueue()
        def worker():
            try:
                results.put(bool(probe_server(url)))
            except Exception:
                results.put(False)
        threading.Thread(target=worker, daemon=True).start()
        def poll():
            try:
                healthy = results.get_nowait()
            except queue.Empty:
                try:
                    if self.root.winfo_exists():
                        self.root.after(100, poll)
                except tk.TclError:
                    pass
                return
            self._probe_in_flight = False
            try:
                if self.root.winfo_exists():
                    self.set_connection_indicator('green' if healthy else 'red')
                    self.root.after(30000, self._start_server_probe)
            except tk.TclError:
                pass
        self.root.after(100, poll)

    def set_connection_indicator(self, status):
        """Antialiased high-resolution status light; green only after real health probe."""
        from PIL import Image, ImageDraw
        colors = {'gray': ('#E2E8F0','#64748B'), 'red': ('#FEE2E2','#EF4444'),
                  'yellow': ('#FEF3C7','#F59E0B'),
                  'green': ('#DCFCE7','#22C55E')}
        if status not in colors:
            raise ValueError('Invalid connection state')
        halo, core = colors[status]
        scale=4
        img=Image.new('RGBA',(38*scale,38*scale),(0,0,0,0))
        draw=ImageDraw.Draw(img)
        draw.ellipse((2*scale,2*scale,36*scale,36*scale),fill=halo)
        draw.ellipse((8*scale,8*scale,30*scale,30*scale),fill=core)
        draw.ellipse((12*scale,11*scale,18*scale,17*scale),fill=(255,255,255,170))
        img=img.resize((76,76),Image.Resampling.LANCZOS)
        self._connection_status_image=ctk.CTkImage(light_image=img,dark_image=img,size=(38,38))
        self.connection_indicator.configure(image=self._connection_status_image)

    def central_management_dialog(self):
        """Modern, read-only central workspace; remote status is never fabricated."""
        self._close_embedded()
        screen = ctk.CTkFrame(self.content_host, fg_color='#F2F5FA', corner_radius=0)
        screen.place(relx=0, rely=0, relwidth=1, relheight=1)
        screen.lift()
        self.current_embedded = screen
        scroll = ctk.CTkScrollableFrame(screen, fg_color='#F2F5FA')
        scroll.pack(fill='both', expand=True, padx=15, pady=12)

        hero = ctk.CTkFrame(scroll, fg_color='#102743', corner_radius=20)
        hero.pack(fill='x', padx=6, pady=(2, 16))
        ctk.CTkLabel(hero, text='مركز قيادة الفروع', font=('Segoe UI', 25, 'bold'),
                     text_color='white', anchor='e').pack(fill='x', padx=24, pady=(22, 5))
        ctk.CTkLabel(hero, text='الإدارة المركزية  /  نظرة موحدة  /  وضع المعاينة المحلية',
                     font=('Segoe UI', 12), text_color='#93D9FC', anchor='e').pack(fill='x', padx=24, pady=(0, 20))

        local_id = self.branch_config.get('branch_id')
        pending = None
        try:
            from network_readiness import list_drafts
            drafts = list_drafts(APP_DIR / 'pmp_offline_drafts.db', branch_id=local_id)
            pending = sum(1 for d in drafts if d.get('status') == 'draft')
        except Exception:
            pass

        stats = ctk.CTkFrame(scroll, fg_color='transparent')
        stats.pack(fill='x', padx=1, pady=(0, 14))
        for col in range(3):
            stats.grid_columnconfigure(col, weight=1, uniform='centralstats')
        summary = [
            ('الفروع المسجلة', '02', 'السودان  •  مصر', '#1764D8'),
            ('المسودات المحلية', str(pending) if pending is not None else '—',
             'للفرع الحالي فقط', '#0D9488'),
            ('المزامنة السحابية', 'غير مفعلة', 'لا توجد حالة اتصال مؤكدة', '#D97706'),
        ]
        for i, (label, value, hint, accent) in enumerate(summary):
            tile = ctk.CTkFrame(stats, fg_color='white', corner_radius=16,
                                border_width=1, border_color='#DFE7F0')
            tile.grid(row=0, column=2-i, sticky='nsew', padx=5, pady=3)
            ctk.CTkFrame(tile, fg_color=accent, height=4, corner_radius=2).pack(fill='x', padx=15, pady=(14, 11))
            ctk.CTkLabel(tile, text=label, font=('Segoe UI', 11), text_color='#64748B',
                         anchor='e').pack(fill='x', padx=16)
            ctk.CTkLabel(tile, text=value, font=('Segoe UI', 22, 'bold'), text_color='#102743',
                         anchor='e').pack(fill='x', padx=16, pady=(5, 3))
            ctk.CTkLabel(tile, text=hint, font=('Segoe UI', 10), text_color='#64748B',
                         anchor='e').pack(fill='x', padx=16, pady=(0, 15))

        ctk.CTkLabel(scroll, text='متابعة الفروع', font=('Segoe UI', 21, 'bold'),
                     text_color='#102743', anchor='e').pack(fill='x', padx=10, pady=(10, 9))
        branch_grid = ctk.CTkFrame(scroll, fg_color='transparent')
        branch_grid.pack(fill='x')
        for col in range(2):
            branch_grid.grid_columnconfigure(col, weight=1, uniform='branches')
        for i, (branch_id, name, symbol) in enumerate([
            ('SD-001', 'فرع السودان', 'SDG'), ('EG-001', 'فرع مصر', 'EGP')
        ]):
            local = branch_id == local_id
            card = ctk.CTkFrame(branch_grid, fg_color='white', corner_radius=18,
                                border_width=1, border_color='#DFE7F0')
            card.grid(row=0, column=1-i, sticky='nsew', padx=5, pady=5)
            ctk.CTkLabel(card, text=f'{name}    •    {branch_id}',
                         font=('Segoe UI', 19, 'bold'), text_color='#102743',
                         anchor='e').pack(fill='x', padx=20, pady=(19, 8))
            ctk.CTkLabel(card, text=('الفرع الحالي  •  بيانات محلية' if local else
                                      'فرع خارجي  •  بانتظار الربط السحابي'),
                         font=('Segoe UI', 12), text_color='#1764D8' if local else '#B45309',
                         anchor='e').pack(fill='x', padx=20, pady=4)
            ctk.CTkLabel(card, text=f'العملة: {symbol}     |     حالة الاتصال: غير مؤكدة',
                         font=('Segoe UI', 11), text_color='#64748B',
                         anchor='e').pack(fill='x', padx=20, pady=(2, 17))

        actions = ctk.CTkFrame(scroll, fg_color='white', corner_radius=18,
                               border_width=1, border_color='#DFE7F0')
        actions.pack(fill='x', padx=5, pady=(16, 9))
        ctk.CTkLabel(actions, text='مساحة العمل', font=('Segoe UI', 19, 'bold'),
                     text_color='#102743', anchor='e').pack(fill='x', padx=20, pady=(16, 5))
        ctk.CTkLabel(actions, text='المسودات محفوظة محلياً. لا يتم اعتماد أو ترحيل أي حركة مالية تلقائياً.',
                     font=('Segoe UI', 11), text_color='#64748B', anchor='e').pack(fill='x', padx=20, pady=(0, 10))
        buttons = ctk.CTkFrame(actions, fg_color='transparent')
        buttons.pack(fill='x', padx=14, pady=(0, 16))
        ctk.CTkButton(buttons, text='فتح المسودات المحلية  ◈',
                      command=self.offline_drafts_dialog, height=42, corner_radius=12,
                      fg_color='#1764D8', hover_color='#1250B0',
                      font=('Segoe UI', 12, 'bold')).pack(side='right', padx=5)
        ctk.CTkButton(buttons, text='العودة للوحة التحكم  ⌂',
                      command=self.show_home, height=42, corner_radius=12,
                      fg_color='#E0F2FE', text_color='#102743', hover_color='#BAE6FD',
                      font=('Segoe UI', 12, 'bold')).pack(side='right', padx=5)

    def offline_drafts_dialog(self):
        """Offline draft workspace; never posts to accounting or inventory."""
        from network_readiness import (create_draft, list_drafts, update_draft, delete_draft)
        path = APP_DIR / 'pmp_offline_drafts.db'
        branch_id = self.branch_config['branch_id']  # Local configuration; server must independently authorize this branch.
        win = ctk.CTkToplevel(self.root)
        win.title('المسودات المعلّقة')
        win.geometry('850x640')
        win.transient(self.root)
        outer = ctk.CTkScrollableFrame(win, fg_color='transparent')
        outer.pack(fill='both', expand=True, padx=12, pady=12)
        ctk.CTkLabel(outer, text='المسودات المعلّقة — ' + self.branch_config['branch_name'], font=('Segoe UI', 21, 'bold'),
                     text_color=M3['ink']).pack(anchor='e', pady=8)
        ctk.CTkLabel(outer, text='المسودات محلية حتى إرسالها صراحةً؛ السيرفر يستلمها للمراجعة دون ترحيل مالي.',
                     text_color='#9A4D00', wraplength=760, justify='right').pack(anchor='e', pady=5)
        kind_names = {'invoice':'فاتورة', 'stock_receipt':'وارد مخزون', 'cash_movement':'حركة مالية'}
        names_kind = {v:k for k,v in kind_names.items()}
        kind = ctk.StringVar(value='فاتورة')
        ctk.CTkLabel(outer, text='نوع المسودة', anchor='e').pack(fill='x', pady=(8,2))
        ctk.CTkOptionMenu(outer, values=list(names_kind), variable=kind).pack(fill='x')
        ctk.CTkLabel(outer, text='عنوان المسودة', anchor='e').pack(fill='x', pady=(8,2))
        title = ctk.CTkEntry(outer, justify='right', placeholder_text='مثلاً: فاتورة العميل أحمد')
        title.pack(fill='x')
        ctk.CTkLabel(outer, text='تفاصيل إضافية (اختيارية)', anchor='e').pack(fill='x', pady=(8,2))
        details = ctk.CTkTextbox(outer, height=105)
        details.pack(fill='x')
        # Structured draft fields. These are local proposals, NEVER financial postings.
        structured = ctk.CTkFrame(outer, fg_color='transparent')
        structured.pack(fill='x', pady=8)
        entries = {}
        kind_fields = {
            'invoice': [('customer','العميل'),('items','الأصناف (JSON: قائمة الصنف والكمية والسعر)'),('currency','العملة SDG/EGP/USD')],
            'stock_receipt': [('warehouse','المخزن'),('color','اللون'),('size','الوزن/العبوة'),('quantity','عدد الكراتين')],
            'cash_movement': [('cashbox','الخزنة'),('direction','نوع الحركة: وارد أو منصرف'),('currency','العملة SDG/EGP/USD'),('amount','المبلغ'),('memo','البيان')],
        }
        def redraw_fields():
            for child in structured.winfo_children(): child.destroy()
            entries.clear()
            for key, label in kind_fields[names_kind[kind.get()]]:
                ctk.CTkLabel(structured, text=label, anchor='e').pack(fill='x', pady=(5,1))
                widget = ctk.CTkEntry(structured, justify='right')
                widget.pack(fill='x')
                entries[key] = widget
        kind.trace_add('write', lambda *_: redraw_fields())
        redraw_fields()
        selected = {'id':None}
        table = ttk.Treeview(outer, columns=('kind','title','date','status'), show='headings', height=9, selectmode='browse')
        for key, label, width in [('kind','النوع',110),('title','العنوان',400),('date','التاريخ',190),('status','الحالة',130)]:
            table.heading(key,text=label)
            table.column(key,width=width,anchor='e')
        def reload():
            for row in table.get_children(): table.delete(row)
            try:
                for item in list_drafts(path, branch_id=branch_id):
                    table.insert('', 'end', iid=item['draft_id'], values=(kind_names.get(item['kind'],item['kind']),
                                 item['payload'].get('title',''),item['updated_at'][:19], 'مستلمة' if item['status']=='submitted' else 'محلية'))
            except Exception as exc:
                messagebox.showerror('المسودات', str(exc), parent=win)
        def clear():
            selected['id']=None
            title.delete(0,'end'); details.delete('1.0','end')
            for widget in entries.values(): widget.delete(0,'end')
            table.selection_remove(*table.selection())
        def load_selected(_event=None):
            ids=table.selection()
            if not ids:return
            try:
                item=next(x for x in list_drafts(path,branch_id=branch_id) if x['draft_id']==ids[0])
                selected['id']=item['draft_id'] if item['status']=='draft' else None; kind.set(kind_names[item['kind']])
                title.delete(0,'end');title.insert(0,item['payload'].get('title',''))
                details.delete('1.0','end');details.insert('1.0',item['payload'].get('details',''))
                for key, widget in entries.items():
                    widget.insert(0, str(item['payload'].get('data',{}).get(key,'')))
            except Exception as exc: messagebox.showerror('المسودات',str(exc),parent=win)
        table.bind('<<TreeviewSelect>>',load_selected)
        def save():
            payload={'title':title.get().strip(),'details':details.get('1.0','end-1c').strip(),
                     'data':{key:widget.get().strip() for key,widget in entries.items()}}
            if not payload['title']:
                messagebox.showwarning('المسودات','اكتب عنوان المسودة أولاً',parent=win);return
            try:
                from pmp_branch import validate_structured_draft
                validate_structured_draft(names_kind[kind.get()], payload)
                if selected['id']:
                    update_draft(path,draft_id=selected['id'],branch_id=branch_id,
                                 author=self.user['username'],kind=names_kind[kind.get()],payload=payload)
                else:
                    create_draft(path,branch_id=branch_id,author=self.user['username'],
                                 kind=names_kind[kind.get()],payload=payload)
                clear();reload()
            except Exception as exc:messagebox.showerror('المسودات',str(exc),parent=win)
        def remove():
            if not selected['id']:return
            if not messagebox.askyesno('حذف مسودة','هل تريد حذف المسودة المحددة؟',parent=win):return
            try:
                delete_draft(path,draft_id=selected['id'],branch_id=branch_id,author=self.user['username'])
                clear();reload()
            except Exception as exc:messagebox.showerror('المسودات',str(exc),parent=win)
        def send_selected():
            ids = table.selection()
            if not ids: return
            draft_id = ids[0]
            draft = next((x for x in list_drafts(path, branch_id=branch_id) if x['draft_id']==draft_id),None)
            if not draft or draft['status']!='draft':
                messagebox.showinfo('المزامنة','المسودة سبق إرسالها',parent=win); return
            from tkinter import simpledialog
            server_url = simpledialog.askstring('سيرفر PMP','عنوان السيرفر HTTPS (بدون /health)',
                                                initialvalue='https://authentic-analysis-production-34a5.up.railway.app',parent=win)
            if not server_url: return
            use_account = messagebox.askyesno('طريقة المصادقة',
                'هل تم إنشاء حساب مركزي لك على السيرفر؟\nنعم: الدخول باسم المستخدم وكلمة المرور.\nلا: رمز الفرع القديم (قد يرجع 403).',parent=win)
            username = password = None
            if use_account:
                username = simpledialog.askstring('الحساب المركزي','اسم المستخدم المركزي:',parent=win)
                if not username: return
                password = simpledialog.askstring('الحساب المركزي','كلمة المرور:',show='*',parent=win)
                if not password: return
            sync_btn.configure(state='disabled', text='جاري الإرسال...')
            def worker():
                try:
                    if use_account:
                        from draft_sync import submit_draft_with_login
                        result=submit_draft_with_login(path,branch_id,draft_id,server_url,username,password)
                    else:
                        from draft_sync import submit_draft
                        result=submit_draft(path,branch_id,draft_id,server_url)
                    outcome=('ok',result['status'])
                except Exception as exc:
                    outcome=('error',str(exc))
                def done():
                    if not win.winfo_exists(): return
                    sync_btn.configure(state='normal',text='إرسال المحددة للسيرفر')
                    if outcome[0]=='ok':
                        clear();reload()
                        messagebox.showinfo('المزامنة','تم استلام المسودة للمراجعة: '+outcome[1],parent=win)
                    else: messagebox.showerror('المزامنة',outcome[1],parent=win)
                self.root.after(0,done)
            threading.Thread(target=worker,daemon=True).start()
        buttons=ctk.CTkFrame(outer,fg_color='transparent')
        buttons.pack(fill='x',pady=12)
        ctk.CTkButton(buttons,text='حفظ المسودة',command=save).pack(side='right',padx=5)
        ctk.CTkButton(buttons,text='مسودة جديدة',command=clear).pack(side='right',padx=5)
        ctk.CTkButton(buttons,text='حذف المحددة',command=remove,fg_color='#A83B3B').pack(side='right',padx=5)
        sync_btn=ctk.CTkButton(buttons,text='إرسال المحددة للسيرفر',command=send_selected)
        sync_btn.pack(side='right',padx=5)
        table.pack(fill='both',expand=True,pady=10)
        reload()

    def show_audit(self):
        if self.user['role'] != 'manager':
            return
        popup = self._embedded_screen('show_audit')
        popup.title('PMP | سجل التدقيق')
        popup.geometry('1050x630')
        popup.minsize(720, 450)
        popup.transient(self.root)
        popup.configure(fg_color=M3['background'])
        ctk.CTkLabel(popup, text='آخر 500 عملية مسجلة في النظام', text_color=M3['muted'],
                     font=('Segoe UI', 12), anchor='e').pack(fill='x', padx=26, pady=(4, 8))
        card = ctk.CTkFrame(popup, fg_color='white', corner_radius=16,
                            border_width=1, border_color='#DDE6F1')
        card.pack(fill='both', expand=True, padx=18, pady=(0, 18))
        holder = tk.Frame(card, bg='white')
        holder.pack(fill='both', expand=True, padx=16, pady=16)
        style = ttk.Style(popup)
        style.configure('PMPAudit.Treeview', rowheight=31, font=('Segoe UI', 10),
                        background='white', fieldbackground='white', foreground='#17324D')
        style.configure('PMPAudit.Treeview.Heading', font=('Segoe UI', 11, 'bold'))
        cols = ('time', 'user', 'action', 'detail')
        tree = ttk.Treeview(holder, columns=cols, show='headings', style='PMPAudit.Treeview')
        for key, label, width in [('time', 'التاريخ', 165), ('user', 'المستخدم', 150),
                                  ('action', 'العملية', 170), ('detail', 'التفاصيل', 480)]:
            tree.heading(key, text=label)
            tree.column(key, width=width, minwidth=110, anchor='e' if key == 'detail' else 'center')
        yscroll = ttk.Scrollbar(holder, orient='vertical', command=tree.yview)
        xscroll = ttk.Scrollbar(holder, orient='horizontal', command=tree.xview)
        tree.configure(yscrollcommand=yscroll.set, xscrollcommand=xscroll.set)
        holder.grid_rowconfigure(0, weight=1)
        holder.grid_columnconfigure(0, weight=1)
        tree.grid(row=0, column=0, sticky='nsew')
        yscroll.grid(row=0, column=1, sticky='ns')
        xscroll.grid(row=1, column=0, sticky='ew')
        with connection() as con:
            rows = con.execute('SELECT created_at,username,action,detail FROM audit_log ORDER BY id DESC LIMIT 500').fetchall()
        for index, row in enumerate(rows):
            tree.insert('', 'end', values=row, tags=('even' if index % 2 == 0 else 'odd',))
        tree.tag_configure('even', background='#F3F7FC')
        tree.tag_configure('odd', background='#FFFFFF')

    def save(self):
        if not self._validate_form_fields():
            messagebox.showwarning('حقول مطلوبة', 'راجع الحقول المحددة باللون الأحمر قبل الحفظ.', parent=self.root)
            return
        if self.user['role'] not in ('manager','accountant','deputy'):
            messagebox.showerror('الصلاحيات','غير مصرح بتسجيل الحركات')
            return
        try:
            data={key:widget.get().strip() for key,widget in self.fields.items()}
            date.fromisoformat(data['date'])
            if data['direction'] not in ('وارد','منصرف'):
                raise ValueError('نوع الحركة غير صحيح')
            if data['cashbox'] not in CASHBOXES:
                raise ValueError('الخزنة غير صحيحة')
            if data['currency'] not in ('SDG','EGP','USD'):
                raise ValueError('العملة غير صحيحة')
            try:
                amount_decimal=Decimal(data['amount'])
            except InvalidOperation:
                raise ValueError('أدخل مبلغاً رقمياً صحيحاً')
            if not amount_decimal.is_finite() or amount_decimal<=0 or amount_decimal.as_tuple().exponent < -2:
                raise ValueError('المبلغ يجب أن يكون موجباً وبحد أقصى منزلتين عشريتين')
            if amount_decimal>Decimal('999999999999.99'):
                raise ValueError('المبلغ أكبر من الحد المسموح')
            if data['reference'].upper().startswith(('TRF:', 'V7:', 'INV:')):
                raise ValueError('رقم المستند لا يجوز أن يبدأ بـ TRF: أو V7: أو INV: (محجوز للنظام)')
            if not data['description'] or not data['reference']:
                raise ValueError('البيان ورقم المستند مطلوبان')
            amount_cents=int(amount_decimal*100)
            # BEGIN IMMEDIATE prevents two writers from simultaneously checking the same balance.
            with connection() as con:
                con.execute('BEGIN IMMEDIATE')
                require_open_day(con,data['date'],data['cashbox'],data['currency'])
                if con.execute('SELECT 1 FROM movements WHERE reference=?',(data['reference'],)).fetchone():
                    raise ValueError('رقم المستند مسجل من قبل؛ لم يتم حفظ الحركة')
                existing=con.execute('SELECT direction,amount FROM movements WHERE cashbox=? AND currency=?',(data['cashbox'],data['currency'])).fetchall()
                balance_cents=sum((1 if direction=='وارد' else -1)*int((Decimal(str(amount))*100).quantize(Decimal('1'),rounding=ROUND_HALF_UP)) for direction,amount in existing)
                if data['direction']=='منصرف' and amount_cents>balance_cents:
                    raise ValueError(f'الرصيد غير كافٍ. المتاح: {format_money(Decimal(balance_cents)/100)} {data["currency"]}')
                cur=con.execute('INSERT INTO movements(tx_date,cashbox,currency,direction,amount,description,created_by,reference) VALUES(?,?,?,?,?,?,?,?)',
                    (data['date'],data['cashbox'],data['currency'],data['direction'],float(amount_decimal),data['description'],self.user['username'],data['reference']))
                con.execute('INSERT INTO audit_log(username,action,detail) VALUES(?,?,?)',
                    (self.user['username'],'CREATE_MOVEMENT',f'الحركة {cur.lastrowid} / المستند {data["reference"]}'))
            for field in ('amount','description','reference'):
                self.fields[field].delete(0,'end')
            self.refresh()
            messagebox.showinfo('✓ تم حفظ الحركة', 'تم حفظ الحركة بنجاح وتحديث الأرصدة.', parent=self.root)
        except sqlite3.IntegrityError:
            messagebox.showerror('رقم مستند مكرر', 'رقم المستند مسجل من قبل؛ لم يتم حفظ الحركة.', parent=self.root)
        except (ValueError, sqlite3.Error) as exc:
            messagebox.showerror('تعذر حفظ الحركة', str(exc), parent=self.root)
    def refresh(self):
        self.load_movements()
        with connection() as con:
            balances=con.execute("SELECT cashbox,currency,SUM(CASE WHEN direction='وارد' THEN amount ELSE -amount END) FROM movements GROUP BY cashbox,currency ORDER BY cashbox,currency").fetchall()
        with connection() as con:
            totals=con.execute("SELECT COALESCE(SUM(CASE WHEN direction='وارد' THEN amount ELSE 0 END),0),COALESCE(SUM(CASE WHEN direction='منصرف' THEN amount ELSE 0 END),0),COUNT(*) FROM movements").fetchone()
        self.stat_labels['incoming'].configure(text='حسب العملة أدناه')
        self.stat_labels['outgoing'].configure(text='حسب العملة أدناه')
        self.stat_labels['count'].configure(text=str(totals[2]))
        for widget in self.balance_cards.winfo_children():
            widget.destroy()
        for col in range(3):
            self.balance_cards.grid_columnconfigure(col, weight=1, uniform='balances')
        if not balances:
            ctk.CTkLabel(self.balance_cards, text='لا توجد حركات بعد', text_color='#64748B').grid(row=0, column=0, columnspan=3, pady=12)
        for idx, (cashbox, currency, amount) in enumerate(balances):
            card = ctk.CTkFrame(self.balance_cards, fg_color='#F7FAFE', corner_radius=13,
                                border_width=1, border_color='#D7E4F3')
            card.grid(row=idx // 3, column=2-(idx % 3), sticky='ew', padx=5, pady=6)
            ctk.CTkLabel(card, text=f'{cashbox}  |  {currency}', text_color='#36577B',
                         font=('Segoe UI', 11, 'bold'), anchor='e').pack(fill='x', padx=15, pady=(11, 2))
            ctk.CTkLabel(card, text=format_money(amount), text_color=M3['ink'] if amount >= 0 else '#B42335',
                         font=('Segoe UI', 19, 'bold'), anchor='e').pack(fill='x', padx=15, pady=(0, 12))

def _startup_log(message):
    try:
        log_path = Path(os.environ.get('LOCALAPPDATA', str(Path.home()))) / 'PMP_Cash_Test' / 'startup.log'
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with log_path.open('a', encoding='utf-8') as output:
            output.write(datetime.now().isoformat(timespec='seconds') + ' ' + message + '\n')
    except Exception:
        pass


def _show_login(root):
    _startup_log('Preparing login window')
    root.deiconify()
    root.update_idletasks()
    Login(root)
    root.lift()
    root.focus_force()
    _startup_log('Login window constructed; starting mainloop')
    root.mainloop()
    _startup_log('Mainloop exited')


def _first_manager_window(root):
    """Visible, single-window first-run wizard; no console or hidden modal dialogs."""
    _startup_log('Showing first-run manager form')
    root.title('PMP Cash - إعداد المدير المحلي')
    root.geometry('520x520')
    root.minsize(480, 480)
    root.deiconify()
    root.lift()

    panel = ctk.CTkFrame(root, fg_color='white')
    panel.pack(fill='both', expand=True, padx=24, pady=24)
    ctk.CTkLabel(panel, text='إنشاء حساب المدير المحلي',
                 font=('Segoe UI', 21, 'bold')).pack(pady=(22, 6))
    ctk.CTkLabel(panel, text='للتجربة على هذا الجهاز فقط - ليس حساب Railway',
                 font=('Segoe UI', 11)).pack(pady=(0, 12))

    fields = {}
    for key, label, default, masked in (
        ('username', 'اسم المستخدم', 'manager', False),
        ('display', 'اسم المدير الظاهر', 'المدير', False),
        ('password', 'كلمة المرور (12 حرفاً على الأقل)', '', True),
        ('confirm', 'تأكيد كلمة المرور', '', True),
    ):
        ctk.CTkLabel(panel, text=label, anchor='e').pack(fill='x', padx=30, pady=(6, 2))
        entry = ctk.CTkEntry(panel, width=370, show='*' if masked else '')
        entry.pack(fill='x', padx=30)
        if default:
            entry.insert(0, default)
        fields[key] = entry

    status = ctk.CTkLabel(panel, text='', text_color='#B42335', wraplength=420)
    status.pack(pady=(12, 2))

    def save():
        username = fields['username'].get().strip()
        display = fields['display'].get().strip()
        pw = fields['password'].get()
        confirm = fields['confirm'].get()
        if not username or not display:
            status.configure(text='اسم المستخدم واسم المدير مطلوبان')
            return
        if len(pw) < 12 or pw != confirm:
            status.configure(text='كلمة المرور يجب أن تكون 12 حرفاً على الأقل ومتطابقة مع التأكيد')
            return
        try:
            with connection() as con:
                # Do not create a second manager if another instance initialized first.
                if con.execute('SELECT 1 FROM users LIMIT 1').fetchone():
                    status.configure(text='يوجد حساب بالفعل. أغلق البرنامج وافتحه مجدداً.')
                    return
                con.execute('INSERT INTO users(username,display_name,role,password_hash) VALUES(?,?,?,?)',
                            (username, display, 'manager', password_hash(pw)))
            _startup_log('Local manager created; opening login')
            panel.destroy()
            _show_login(root)
        except Exception:
            _startup_log('First-run error\n' + traceback.format_exc())
            status.configure(text='تعذر إنشاء الحساب. راجع startup.log')

    ctk.CTkButton(panel, text='حفظ وفتح تسجيل الدخول', command=save,
                  height=42).pack(pady=(5, 18))
    fields['username'].focus_set()
    root.mainloop()
    _startup_log('First-run mainloop exited')


def _run_application():
    _startup_log('Application startup')
    setup()
    _startup_log('Database setup completed')
    if len(sys.argv) > 1 and sys.argv[1] == '--init-manager':
        create_first_manager()
        return
    with connection() as con:
        count = con.execute('SELECT COUNT(*) FROM users').fetchone()[0]
    _startup_log('Local user count: ' + str(count))
    root = ctk.CTk(fg_color=M3['background'])
    if count == 0:
        _first_manager_window(root)
    else:
        _show_login(root)


if __name__ == '__main__':
    try:
        _run_application()
    except Exception:
        details = traceback.format_exc()
        _startup_log('FATAL ERROR\n' + details)
        try:
            import tkinter.messagebox as _native_messagebox
            _native_messagebox.showerror('PMP Startup Error',
                'Startup failed. See startup.log in %LOCALAPPDATA%\\PMP_Cash_Test.\n\n' + details[-1800:])
        except Exception:
            pass
        raise
