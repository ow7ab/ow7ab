"""PMP poster pricing, stored separately from invoice/product prices."""
import sqlite3
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

WEIGHTS = ('250', '900')
KINDS = ('أبيض', 'ألوان', 'فضي', 'ذهبي', 'ورنيش')
DEFAULTS = {'250': (500000,490000,510000,520000,495000),
            '900': (385000,375000,390000,400000,375000)}


def init_prices(db_path):
    with sqlite3.connect(str(db_path), timeout=15) as con:
        con.execute('CREATE TABLE IF NOT EXISTS poster_prices (weight TEXT NOT NULL, kind TEXT NOT NULL, price INTEGER NOT NULL CHECK(price >= 0), PRIMARY KEY(weight,kind))')
        con.execute('CREATE TABLE IF NOT EXISTS poster_settings (setting TEXT PRIMARY KEY, value TEXT NOT NULL)')
        for weight in WEIGHTS:
            for kind, price in zip(KINDS, DEFAULTS[weight]):
                con.execute('INSERT OR IGNORE INTO poster_prices(weight,kind,price) VALUES (?,?,?)', (weight,kind,price))
        con.execute("INSERT OR IGNORE INTO poster_settings(setting,value) VALUES ('discount','15')")


def load_prices(db_path):
    init_prices(db_path)
    with sqlite3.connect(str(db_path)) as con:
        rows = con.execute('SELECT weight,kind,price FROM poster_prices').fetchall()
        discount = con.execute("SELECT value FROM poster_settings WHERE setting='discount'").fetchone()[0]
    return {(w,k): int(v) for w,k,v in rows}, int(discount)


def save_prices(db_path, prices, discount):
    if not isinstance(discount, int) or not 0 <= discount <= 100:
        raise ValueError('نسبة الخصم يجب أن تكون بين 0 و100')
    validated = {}
    for w in WEIGHTS:
        for k in KINDS:
            v = prices[(w,k)]
            if not isinstance(v, int) or v < 0:
                raise ValueError('السعر يجب أن يكون رقماً صحيحاً غير سالب')
            validated[(w,k)] = v
    with sqlite3.connect(str(db_path), timeout=15) as con:
        for (w,k), v in validated.items():
            con.execute('INSERT INTO poster_prices(weight,kind,price) VALUES (?,?,?) ON CONFLICT(weight,kind) DO UPDATE SET price=excluded.price', (w,k,v))
        con.execute("INSERT INTO poster_settings(setting,value) VALUES ('discount',?) ON CONFLICT(setting) DO UPDATE SET value=excluded.value", (str(discount),))


def _font(size):
    candidates = [
        'C:/Windows/Fonts/arialbd.ttf',
        'C:/Windows/Fonts/Arial.ttf',
        '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',
    ]
    for path in candidates:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def render_poster(template_path, prices, discount):
    """Use the supplied poster as a fixed background; replace only numeric fields."""
    image = Image.open(template_path).convert('RGB')
    if image.size != (1024, 1536):
        image = image.resize((1024, 1536), Image.Resampling.LANCZOS)
    draw = ImageDraw.Draw(image)
    f = _font(43)
    ys = (575, 650, 727, 804, 879)
    # Preserve the table columns and lines. Backgrounds are sampled from each row.
    for weight, x1, x2, center in [('900', 26, 279, 148), ('250', 517, 759, 642)]:
        for idx, (kind, y) in enumerate(zip(KINDS, ys)):
            color = image.getpixel((x1 + 6, y + 2))
            draw.rectangle((x1, y-28, x2, y+30), fill=color)
            draw.text((center, y), str(prices[(weight,kind)]), font=f,
                      fill='#52002E', anchor='mm', stroke_width=0)
    # Replace the promotional percentage on the red banner without changing its copy or imagery.
    # The gold plaque conceals the old number and remains within the original red banner.
    draw.rounded_rectangle((470, 908, 855, 1107), radius=23, fill='#97000F', outline='#E7A21B', width=5)
    draw.text((648, 1018), f'{discount}%', font=_font(120 if discount < 100 else 99),
              fill='#FFD51C', anchor='mm', stroke_width=3, stroke_fill='#A34A00')
    return image
