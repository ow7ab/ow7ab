"""PMP V14 - professional dropdown lists (Figma style).

Every drop-down in the program (customtkinter ComboBox / OptionMenu and the native ttk.Combobox)
is replaced by ``PMPDropdown``:

    +--------------------------------------+
    | [v]                        selected  |   field: white, rounded, blue square chevron on the left
    +--------------------------------------+
    | pop-up: rounded rows, hover tint, the current choice highlighted in blue,
    |         type-to-filter search box when the list is long, scrolling with the wheel / keys,
    |         opens upward when there is no room below, closes on Esc / click-away / choice.

``install()`` swaps the classes inside ``customtkinter`` and ``tkinter.ttk`` so that all existing
call sites keep working unchanged (same ``get`` / ``set`` / ``configure(values=...)`` /
``['values']`` / ``bind('<<ComboboxSelected>>')`` / ``command=`` API).  Business logic is untouched.
"""
import time
import tkinter as tk
from tkinter import ttk

import customtkinter as ctk

C = {'primary': '#1764D8', 'primary_hover': '#1250B0', 'ink': '#18263D', 'muted': '#78869B',
     'outline': '#DCE5F0', 'hover': '#EEF4FD', 'selected': '#E3EEFC', 'white': '#FFFFFF',
     'search_bg': '#F5F8FC', 'disabled': '#A9B4C4'}
FONT = 'Segoe UI'
ROW_H = 36          # height of one list row
MAX_ROWS = 8        # rows visible before the list scrolls
SEARCH_FROM = 10    # lists longer than this get a search box
PAD = 4


# ---- pure helpers (unit-tested without a display) ----------------------------------------------
def filter_values(values, needle):
    needle = str(needle or '').strip().lower()
    if not needle:
        return list(values)
    return [v for v in values if needle in str(v).lower()]


def row_at(y, offset, count):
    """Index of the row under canvas-y ``y`` or -1."""
    index = int((y - PAD + offset) // ROW_H)
    return index if 0 <= index < count else -1


def clamp_offset(offset, count, viewport):
    return max(0, min(offset, max(0, count * ROW_H - viewport)))


def reveal_offset(index, offset, viewport):
    """Scroll offset that brings row ``index`` fully into view."""
    top, bottom = index * ROW_H, (index + 1) * ROW_H
    if top < offset:
        return top
    if bottom > offset + viewport:
        return bottom - viewport
    return offset


def _rrect(canvas, x1, y1, x2, y2, radius, **kw):
    radius = max(0, min(radius, (x2 - x1) / 2, (y2 - y1) / 2))
    pts = [x1 + radius, y1, x2 - radius, y1, x2, y1, x2, y1 + radius, x2, y2 - radius, x2, y2,
           x2 - radius, y2, x1 + radius, y2, x1, y2, x1, y2 - radius, x1, y1 + radius, x1, y1]
    return canvas.create_polygon(pts, smooth=True, **kw)


_ACTIVE = []        # pop-ups currently open (normally 0 or 1)
_HOOKED = set()     # toplevels that already carry the global click-away binding


def _global_click(event):
    path = str(event.widget)
    for popup in list(_ACTIVE):
        if path.startswith(str(popup.top)) or path.startswith(str(popup.owner)):
            continue                      # inside the list, or on the field (its own toggle handles it)
        popup.close()


def _hook(owner):
    root = owner.winfo_toplevel()
    key = str(root)
    if key not in _HOOKED:
        _HOOKED.add(key)
        root.bind('<Button-1>', _global_click, add='+')


class _Event:
    """Minimal event handed to <<ComboboxSelected>> callbacks."""
    def __init__(self, widget):
        self.widget = widget
        self.x = self.y = self.delta = 0


# ---- the pop-up list ---------------------------------------------------------------------------
class _Popup:
    def __init__(self, owner, values, current, on_pick, on_close):
        self.owner, self.values, self.current = owner, list(values), current
        self.on_pick, self.on_close = on_pick, on_close
        self.filtered = list(self.values)
        self.hover = -1
        self.offset = 0
        self.closed = False
        self.opened_at = time.monotonic()
        top = tk.Toplevel(owner)
        top.withdraw()
        top.overrideredirect(True)
        try:
            top.attributes('-topmost', True)
        except tk.TclError:
            pass
        top.configure(bg=C['outline'])
        self.top = top
        inner = tk.Frame(top, bg=C['white'])
        inner.pack(fill='both', expand=True, padx=1, pady=1)

        longest = max((len(str(v)) for v in self.values), default=10)
        owner_w = max(owner.winfo_width(), 120)
        self.width = max(owner_w, min(560, longest * 9 + 64))
        self.visible = min(len(self.values), MAX_ROWS)
        self.view_h = self.visible * ROW_H + 2 * PAD
        self.entry = None
        if len(self.values) > SEARCH_FROM:
            self.var = tk.StringVar()
            self.entry = tk.Entry(inner, textvariable=self.var, justify='right', relief='flat',
                                  font=(FONT, 11), bg=C['search_bg'], fg=C['ink'],
                                  insertbackground=C['ink'], highlightthickness=0, bd=0)
            self.entry.pack(fill='x', padx=8, pady=(8, 2), ipady=6)
            self.var.trace_add('write', lambda *_: self._refilter())
        self.canvas = tk.Canvas(inner, width=self.width - 2, height=self.view_h, bg=C['white'],
                                highlightthickness=0, bd=0)
        self.canvas.pack(fill='both', expand=True)

        for widget in [self.canvas] + ([self.entry] if self.entry else []):
            widget.bind('<Down>', lambda e: self._move(1))
            widget.bind('<Up>', lambda e: self._move(-1))
            widget.bind('<Return>', lambda e: self._pick_hover())
            widget.bind('<Escape>', lambda e: self.close())
        self.canvas.bind('<Motion>', self._motion)
        self.canvas.bind('<Leave>', self._leave)
        self.canvas.bind('<Button-1>', self._click)
        self.canvas.bind('<MouseWheel>', self._wheel)
        self.canvas.bind('<Button-4>', lambda e: self._scroll(-1))
        self.canvas.bind('<Button-5>', lambda e: self._scroll(1))
        top.bind('<FocusOut>', lambda e: top.after(90, self._check_focus))
        top.bind('<Destroy>', lambda e: None)

        if self.current in self.filtered:
            self.hover = self.filtered.index(self.current)
            self.offset = clamp_offset(reveal_offset(self.hover, 0, self.view_h - 2 * PAD),
                                       len(self.filtered), self.view_h - 2 * PAD)
        self._draw()
        self._place()
        top.deiconify()
        top.lift()
        _hook(owner)
        _ACTIVE.append(self)
        try:
            (self.entry or self.canvas).focus_force()
        except tk.TclError:
            pass

    # -- geometry
    def _place(self):
        owner, top = self.owner, self.top
        top.update_idletasks()
        height = top.winfo_reqheight()
        x = owner.winfo_rootx()
        y = owner.winfo_rooty() + owner.winfo_height() + 4
        screen_w, screen_h = owner.winfo_screenwidth(), owner.winfo_screenheight()
        if y + height > screen_h - 48:                       # not enough room below: open upward
            y = max(8, owner.winfo_rooty() - height - 4)
        x = max(8, min(x, screen_w - self.width - 8))
        top.geometry(f'{self.width}x{height}+{x}+{y}')

    # -- drawing
    def _draw(self):
        c = self.canvas
        c.delete('all')
        width = self.width - 2
        viewport = self.view_h - 2 * PAD
        items = self.filtered
        if not items:
            c.create_text(width - 18, self.view_h / 2, text='لا توجد نتائج مطابقة', anchor='e',
                          fill=C['muted'], font=(FONT, 11))
            return
        overflow = len(items) * ROW_H > viewport
        right = width - (14 if overflow else 6)
        first = int(self.offset // ROW_H)
        for index in range(first, min(len(items), first + MAX_ROWS + 2)):
            y = PAD + index * ROW_H - self.offset
            if y + ROW_H < 0 or y > self.view_h:
                continue
            text = str(items[index])
            selected = text == self.current
            if selected or index == self.hover:
                _rrect(c, 6, y + 2, right, y + ROW_H - 2, 9,
                       fill=C['selected'] if selected else C['hover'], outline='')
            c.create_text(right - 12, y + ROW_H / 2, text=text, anchor='e',
                          fill=C['primary'] if selected else C['ink'],
                          font=(FONT, 11, 'bold' if selected else 'normal'))
        if overflow:
            total = len(items) * ROW_H
            thumb_h = max(24, viewport * viewport / total)
            thumb_y = PAD + (viewport - thumb_h) * (self.offset / max(1, total - viewport))
            _rrect(c, width - 8, thumb_y, width - 4, thumb_y + thumb_h, 2, fill='#C5D2E3', outline='')

    # -- events
    def _motion(self, event):
        index = row_at(event.y, self.offset, len(self.filtered))
        if index != self.hover:
            self.hover = index
            self._draw()

    def _leave(self, _event):
        self.hover = -1
        self._draw()

    def _click(self, event):
        index = row_at(event.y, self.offset, len(self.filtered))
        if index >= 0:
            self._pick(self.filtered[index])

    def _wheel(self, event):
        self._scroll(-1 if event.delta > 0 else 1)

    def _scroll(self, direction):
        viewport = self.view_h - 2 * PAD
        self.offset = clamp_offset(self.offset + direction * ROW_H * 2, len(self.filtered), viewport)
        self._draw()

    def _move(self, step):
        if not self.filtered:
            return 'break'
        self.hover = max(0, min(len(self.filtered) - 1, (self.hover if self.hover >= 0 else -1) + step))
        viewport = self.view_h - 2 * PAD
        self.offset = clamp_offset(reveal_offset(self.hover, self.offset, viewport), len(self.filtered), viewport)
        self._draw()
        return 'break'

    def _pick_hover(self):
        if self.filtered:
            self._pick(self.filtered[self.hover if self.hover >= 0 else 0])
        return 'break'

    def _refilter(self):
        self.filtered = filter_values(self.values, self.var.get())
        self.hover = 0 if self.filtered else -1
        self.offset = 0
        self._draw()

    def _pick(self, value):
        self.close()
        self.on_pick(value)

    def _check_focus(self):
        if self.closed or time.monotonic() - self.opened_at < 0.3:
            return
        try:
            focused = self.top.focus_get()
        except (KeyError, tk.TclError):
            focused = None
        if focused is None or not str(focused).startswith(str(self.top)):
            self.close()

    def close(self):
        if self.closed:
            return
        self.closed = True
        if self in _ACTIVE:
            _ACTIVE.remove(self)
        try:
            self.top.destroy()
        except tk.TclError:
            pass
        self.on_close()


# ---- the field ---------------------------------------------------------------------------------
class PMPDropdown(ctk.CTkFrame):
    """CTkComboBox / CTkOptionMenu / ttk.Combobox compatible drop-down with the Figma look."""

    _DROPPED = ('dropdown_fg_color', 'dropdown_text_color', 'dropdown_hover_color', 'hover',
                'text_color_disabled', 'dynamic_resizing', 'anchor', 'textvariable')

    def __init__(self, master=None, width=200, height=40, values=(), variable=None, command=None,
                 state='readonly', corner_radius=10, border_width=1, border_color=None, fg_color=None,
                 text_color=None, button_color=None, button_hover_color=None, font=None,
                 dropdown_font=None, placeholder='اختر...', **kwargs):
        textvariable = kwargs.get('textvariable')
        for key in self._DROPPED:
            kwargs.pop(key, None)
        variable = variable or textvariable
        super().__init__(master, width=int(width), height=int(height), corner_radius=corner_radius,
                         border_width=border_width, border_color=border_color or C['outline'],
                         fg_color=fg_color or C['white'], **kwargs)
        self.pack_propagate(False)
        self.grid_propagate(False)
        self._values = [str(v) for v in values]
        self._value = ''
        self._command = command
        self._state = state
        self._popup = None
        self._closed_at = 0.0
        self._handlers = []
        self._text_color = text_color or C['ink']
        self._font = font or (FONT, 12)
        self._placeholder = placeholder
        self._btn_color = button_color or C['primary']
        self._btn_hover = button_hover_color or C['primary_hover']
        self._var = None
        self._trace = None

        side = max(24, int(height) - 12)
        self._chev = ctk.CTkLabel(self, text='▾', width=side, height=side, corner_radius=8,
                                  fg_color=self._btn_color, text_color='#FFFFFF',
                                  font=(FONT, 13, 'bold'))
        self._chev.pack(side='left', padx=(6, 0), pady=6)
        self._label = ctk.CTkLabel(self, text='', anchor='e', font=self._font,
                                   text_color=self._text_color, fg_color='transparent')
        self._label.pack(side='right', fill='both', expand=True, padx=(8, 14))
        for widget in (self, self._chev, self._label):
            try:
                widget.configure(cursor='hand2')
            except (tk.TclError, ValueError):
                pass
            widget.bind('<Button-1>', self._toggle, add='+')
        self._chev.bind('<Enter>', lambda _e: self._chev.configure(fg_color=self._btn_hover), add='+')
        self._chev.bind('<Leave>', lambda _e: self._chev.configure(fg_color=self._btn_color), add='+')
        if variable is not None:
            self._attach(variable)
        self._refresh()

    # -- value API (CTkComboBox / ttk compatible)
    def get(self):
        return self._value

    def set(self, value):
        self._value = '' if value is None else str(value)
        if self._var is not None and self._var.get() != self._value:
            self._var.set(self._value)
        self._refresh()

    def current(self, index=None):
        if index is None:
            return self._values.index(self._value) if self._value in self._values else -1
        if 0 <= int(index) < len(self._values):
            self.set(self._values[int(index)])

    def selection_clear(self, *_args):
        pass

    def _attach(self, variable):
        if self._var is not None and self._trace is not None:
            try:
                self._var.trace_remove('write', self._trace)
            except (tk.TclError, ValueError):
                pass
        self._var = variable
        self._trace = variable.trace_add('write', lambda *_: self._from_variable())
        self._value = str(variable.get())

    def _from_variable(self):
        value = str(self._var.get())
        if value != self._value:
            self._value = value
            self._refresh()

    def _refresh(self):
        shown = self._value or self._placeholder
        disabled = self._state == 'disabled'
        color = C['disabled'] if disabled else (self._text_color if self._value else C['muted'])
        self._label.configure(text=shown, text_color=color)
        self._chev.configure(fg_color=C['disabled'] if disabled else self._btn_color)

    # -- configure / cget / item access
    def configure(self, require_redraw=False, **kwargs):
        if 'values' in kwargs:
            self._values = [str(v) for v in kwargs.pop('values')]
        if 'command' in kwargs:
            self._command = kwargs.pop('command')
        if 'state' in kwargs:
            self._state = kwargs.pop('state')
            self._refresh()
        for key in ('variable', 'textvariable'):
            if key in kwargs:
                self._attach(kwargs.pop(key))
                self._refresh()
        if 'text_color' in kwargs:
            self._text_color = kwargs.pop('text_color')
            self._refresh()
        if 'font' in kwargs:
            self._font = kwargs.pop('font')
            self._label.configure(font=self._font)
        for key in self._DROPPED:
            kwargs.pop(key, None)
        for key in ('button_color', 'button_hover_color'):
            if key in kwargs:
                setattr(self, '_btn_color' if key == 'button_color' else '_btn_hover', kwargs.pop(key))
                self._chev.configure(fg_color=self._btn_color)
        if kwargs:
            super().configure(require_redraw=require_redraw, **kwargs)

    config = configure

    def cget(self, attribute_name):
        if attribute_name == 'values':
            return list(self._values)
        if attribute_name == 'state':
            return self._state
        if attribute_name in ('variable', 'textvariable'):
            return self._var
        if attribute_name == 'command':
            return self._command
        return super().cget(attribute_name)

    def __setitem__(self, key, value):
        self.configure(**{key: value})

    def __getitem__(self, key):
        return self.cget(key)

    def bind(self, sequence=None, command=None, add=None):
        if sequence == '<<ComboboxSelected>>':
            if command is not None:
                self._handlers.append(command)
            return None
        return super().bind(sequence, command, True if add in (None, '+', True) else add)

    # -- pop-up handling
    def _toggle(self, _event=None):
        if self._popup is not None:
            self._popup.close()
            return
        if self._state == 'disabled' or not self._values:
            return
        if time.monotonic() - self._closed_at < 0.25:     # the click that just closed it via focus-out
            return
        super().configure(border_color=C['primary'], border_width=2)
        self._popup = _Popup(self, self._values, self._value, self._choose, self._popup_closed)

    def _popup_closed(self):
        self._popup = None
        self._closed_at = time.monotonic()
        try:
            super().configure(border_color=C['outline'], border_width=1)
        except tk.TclError:
            pass

    def _choose(self, value):
        self.set(value)
        if self._command:
            self._command(value)
        for handler in list(self._handlers):
            handler(_Event(self))

    def destroy(self):
        if self._popup is not None:
            self._popup.close()
        if self._var is not None and self._trace is not None:
            try:
                self._var.trace_remove('write', self._trace)
            except (tk.TclError, ValueError):
                pass
        super().destroy()


class PMPCombobox(PMPDropdown):
    """Drop-in for ttk.Combobox(master, textvariable=, values=, state=, width=<chars>, font=, justify=)."""

    def __init__(self, master=None, textvariable=None, values=(), state='readonly', width=20,
                 font=None, justify='right', height=None, **kwargs):
        for key in ('style', 'exportselection', 'postcommand', 'takefocus', 'cursor'):
            kwargs.pop(key, None)
        super().__init__(master, width=max(150, int(width) * 9 + 46), height=height or 40,
                         values=values, variable=textvariable, state=state, font=font)


def install():
    """Route every existing drop-down class to the Figma-style implementation."""
    ctk.CTkComboBox = PMPDropdown
    ctk.CTkOptionMenu = PMPDropdown
    ttk.Combobox = PMPCombobox


# ---- Figma form helpers (shared by the factory, permissions and other embedded forms) -------------
TONES = {'success': ('#E8F6EF', '#0F7A55'), 'info': ('#E8F0FD', '#18263D'),
         'warn': ('#FFF4DD', '#946200'), 'danger': ('#FDECEC', '#B42318')}


def field_entry(parent, placeholder='', show=None, **kw):
    """Figma text field: white, 10px radius, 1px outline, right-aligned."""
    opts = dict(height=42, corner_radius=10, border_width=1, border_color=C['outline'],
                fg_color=C['white'], text_color=C['ink'], font=(FONT, 12), justify='right',
                placeholder_text=placeholder)
    if show:
        opts['show'] = show
    opts.update(kw)
    return ctk.CTkEntry(parent, **opts)


def primary_button(parent, text, command, width=347, height=48, tone='primary'):
    colors = {'primary': (C['primary'], C['primary_hover'], '#FFFFFF'),
              'soft': ('#ECF2FA', '#DCE8FA', C['ink']),
              'danger': ('#B42318', '#8E1B12', '#FFFFFF')}[tone]
    return ctk.CTkButton(parent, text=text, command=command, width=width, height=height, corner_radius=12,
                         font=(FONT, 14, 'bold'), fg_color=colors[0], hover_color=colors[1], text_color=colors[2])


def banner(parent, text, tone='info'):
    bg, fg = TONES[tone]
    frame = ctk.CTkFrame(parent, fg_color=bg, corner_radius=12)
    ctk.CTkLabel(frame, text=text, font=(FONT, 13, 'bold'), text_color=fg, anchor='e',
                 justify='right', wraplength=760).pack(fill='x', padx=22, pady=16)
    return frame


class FormGrid(ctk.CTkFrame):
    """Figma 3-column labelled form. Right-to-left: the first field sits in the right-most column."""

    def __init__(self, master, columns=3, **kwargs):
        super().__init__(master, fg_color='transparent', **kwargs)
        self.columns = columns
        self._slot = 0                                   # running position, row-major
        for column in range(columns):
            self.grid_columnconfigure(column, weight=1, uniform='formgrid')

    def add(self, label, builder, span=1):
        span = max(1, min(span, self.columns))
        row, pos = divmod(self._slot, self.columns)
        if pos + span > self.columns:                    # not enough room left on this row
            row, pos = row + 1, 0
            self._slot = row * self.columns
        cell = ctk.CTkFrame(self, fg_color='transparent')
        cell.grid(row=row, column=self.columns - pos - span, columnspan=span, sticky='ew',
                  padx=14, pady=(6, 10))
        ctk.CTkLabel(cell, text=label, anchor='e', font=(FONT, 13, 'bold'),
                     text_color=C['ink']).pack(fill='x', pady=(0, 6))
        widget = builder(cell)
        widget.pack(fill='x')
        self._slot += span
        return widget
