import { CustomerStatementResult } from './ErpCalculations';

function getInlineDesktopBridge(): any {
  if (typeof window !== 'undefined' && (window as any).pmpDesktop?.isDesktop) {
    return (window as any).pmpDesktop;
  }
  return null;
}

export function escapeHtml(value: unknown): string {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function triggerPrintHtml(html: string, title = 'مستند رسمي — PMP PAINTS ERP'): void {
  if (getInlineDesktopBridge() && typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('pmp:desktop-print-request', { detail: { title, html } }));
    return;
  }
  const printWindow = typeof window !== 'undefined' ? window.open('', '_blank', 'width=1080,height=850') : null;
  if (printWindow) {
    printWindow.document.open();
    printWindow.document.write(html);
    printWindow.document.close();
    return;
  }

  // Fallback for sandboxed/popup-blocked browsers: hidden print iframe
  if (typeof document !== 'undefined') {
    const iframe = document.createElement('iframe');
    iframe.style.position = 'fixed';
    iframe.style.right = '0';
    iframe.style.bottom = '0';
    iframe.style.width = '0';
    iframe.style.height = '0';
    iframe.style.border = '0';
    iframe.style.visibility = 'hidden';
    document.body.appendChild(iframe);
    const frameDoc = iframe.contentWindow?.document;
    if (frameDoc) {
      frameDoc.open();
      frameDoc.write(html);
      frameDoc.close();
      setTimeout(() => {
        try {
          iframe.contentWindow?.focus();
          iframe.contentWindow?.print();
        } finally {
          setTimeout(() => {
            if (iframe.parentNode) iframe.parentNode.removeChild(iframe);
          }, 2000);
        }
      }, 400);
      return;
    }
  }

  if (typeof window !== 'undefined') {
    window.print();
  }
}

export function sanitizeCsvCell(cell: string | number | undefined | null): string {
  const str = String(cell ?? '');
  // Neutralize spreadsheet CSV formula injection (=, +, -, @, tab, cr)
  return /^[=+\-@\t\r]/.test(str) ? `'${str}` : str;
}

export const ReportExporter = {
  escapeHtml,
  sanitizeCsvCell,

  exportToCsv: (filename: string, headers: string[], rows: (string | number | undefined | null)[][]) => {
    const BOM = '\uFEFF';
    const csvContent =
      BOM +
      [
        headers.map((h) => `"${String(h).replace(/"/g, '""')}"`).join(','),
        ...rows.map((row) =>
          row
            .map((cell) => {
              const safeStr = sanitizeCsvCell(cell);
              return `"${safeStr.replace(/"/g, '""')}"`;
            })
            .join(',')
        )
      ].join('\n');

    const defaultFileName = `${filename.replace(/\.csv$/i, '')}_${new Date().toISOString().slice(0, 10)}.csv`;
    const desktopBridge = getInlineDesktopBridge();
    if (desktopBridge) {
      void desktopBridge
        .saveFile({
          title: 'حفظ ملف Excel / CSV — PMP PAINTS ERP',
          defaultFilename: defaultFileName,
          content: csvContent,
          encoding: 'utf8',
          filters: [
            { name: 'Excel CSV (UTF-8)', extensions: ['csv'] },
            { name: 'All Files', extensions: ['*'] }
          ]
        })
        .then((res: { canceled?: boolean; filePath?: string }) => {
          if (!res.canceled && res.filePath) {
            if (typeof window !== 'undefined') {
              window.dispatchEvent(
                new CustomEvent('pmp:desktop-export-notice', {
                  detail: {
                    filePath: res.filePath,
                    label: 'تم حفظ ملف التقرير (Excel / CSV) بنجاح'
                  }
                })
              );
            }
            void desktopBridge.notify({
              title: 'PMP PAINTS ERP',
              body: `تم حفظ التقرير بنجاح: ${res.filePath}`
            });
          }
        })
        .catch(() => {});
      return;
    }

    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.setAttribute('href', url);
    link.setAttribute('download', defaultFileName);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  },

  exportToPrint: (
    title: string,
    subtitleOrHeaders: string | string[],
    headersOrRows: string[] | (string | number | undefined | null)[][],
    maybeRows?: (string | number | undefined | null)[][]
  ) => {
    const subtitle = Array.isArray(subtitleOrHeaders) ? '' : subtitleOrHeaders;
    const headers = Array.isArray(subtitleOrHeaders) ? subtitleOrHeaders : (headersOrRows as string[]);
    const rows = Array.isArray(subtitleOrHeaders)
      ? (headersOrRows as (string | number | undefined | null)[][])
      : (maybeRows || []);

    const safeTitle = escapeHtml(title);
    const safeSubtitle = escapeHtml(subtitle);

    const html = `
      <!DOCTYPE html>
      <html dir="rtl" lang="ar">
      <head>
        <meta charset="UTF-8" />
        <title>${safeTitle} - PMP PAINTS</title>
        <style>
          @import url('https://fonts.googleapis.com/css2?family=Cairo:wght@400;600;700;800&display=swap');
          body {
            font-family: 'Cairo', sans-serif;
            margin: 24px;
            color: #0f172a;
            background: #ffffff;
          }
          .header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            border-bottom: 3px solid #dc2626;
            padding-bottom: 16px;
            margin-bottom: 24px;
          }
          .brand h1 {
            margin: 0;
            color: #dc2626;
            font-size: 22px;
            font-weight: 800;
          }
          .brand p {
            margin: 4px 0 0;
            font-size: 13px;
            color: #475569;
          }
          .meta {
            text-align: left;
            font-size: 12px;
            color: #475569;
          }
          .report-title {
            margin-bottom: 16px;
          }
          .report-title h2 {
            margin: 0;
            font-size: 18px;
            color: #1e293b;
          }
          .report-title p {
            margin: 4px 0 0;
            font-size: 12px;
            color: #64748b;
          }
          table {
            width: 100%;
            border-collapse: collapse;
            font-size: 12px;
          }
          th {
            background: #0f172a;
            color: #ffffff;
            padding: 10px 8px;
            text-align: right;
            border: 1px solid #cbd5e1;
          }
          td {
            padding: 8px;
            border: 1px solid #cbd5e1;
            color: #1e293b;
          }
          tr:nth-child(even) td {
            background: #f8fafc;
          }
          .footer {
            margin-top: 28px;
            display: flex;
            justify-content: space-between;
            font-size: 11px;
            color: #64748b;
            border-top: 1px solid #e2e8f0;
            padding-top: 12px;
          }
        </style>
      </head>
      <body>
        <div class="header">
          <div class="brand">
            <h1>شركة PMP للبوهيات والدهانات — PMP PAINTS</h1>
            <p>المنظومة الصناعية والمحاسبية الموحدة (السودان • مصر)</p>
          </div>
          <div class="meta">
            <div>تاريخ الإصدار: ${escapeHtml(new Date().toLocaleString('ar-EG'))}</div>
            <div>مستند رسمي معتمد</div>
          </div>
        </div>
        <div class="report-title">
          <h2>${safeTitle}</h2>
          <p>${safeSubtitle}</p>
        </div>
        <table>
          <thead>
            <tr>
              ${headers.map((h) => `<th>${escapeHtml(h)}</th>`).join('')}
            </tr>
          </thead>
          <tbody>
            ${rows
              .map(
                (r) => `
              <tr>
                ${r.map((c) => `<td>${escapeHtml(c ?? '')}</td>`).join('')}
              </tr>
            `
              )
              .join('')}
          </tbody>
        </table>
        <div class="footer">
          <div>اعتماد الإدارة المالية والمراجعة</div>
          <div>PMP PAINTS — مستند رسمي معتمد</div>
        </div>
        <script>
          window.onload = function() {
            setTimeout(function() { window.print(); }, 350);
          };
        </script>
      </body>
      </html>
    `;

    triggerPrintHtml(html, title);
  },

  /**
   * Exports a specific customer's detailed chronological account statement to Excel/CSV (UTF-8 with BOM).
   */
  exportCustomerStatementToCsv: (statement: CustomerStatementResult) => {
    const cust = statement.customer;
    const safeCustName = (cust.name || 'عميل').replace(/\s+/g, '_');
    const headers = [
      'التاريخ',
      'نوع الحركة',
      'المرجع / رقم المستند',
      'البيان والتفاصيل',
      'مدين (+) مسحوبات',
      'دائن (-) سدادات',
      'الرصيد التراكمي المستحق',
      'العملة',
      'الحالة / الخزنة',
      'الموظف المسؤول'
    ];

    const rows: (string | number)[][] = [
      [
        'بيانات العميل',
        cust.name,
        `الهاتف: ${cust.phone || 'غير مسجل'}`,
        `العنوان: ${cust.address || 'غير محدد'}`,
        `إجمالي المسحوبات: ${statement.totalInvoiced.toLocaleString()}`,
        `إجمالي المسدد: ${statement.totalPaid.toLocaleString()}`,
        `الرصيد المتبقي: ${statement.remainingDebt.toLocaleString()}`,
        statement.currency,
        `عدد الفواتير: ${statement.invoiceCount}`,
        `عدد الدفعات: ${statement.paymentCount}`
      ],
      ...statement.entries.map((entry) => [
        entry.dateFormatted,
        entry.typeLabel,
        entry.referenceNo,
        entry.description,
        entry.debit > 0 ? entry.debit : 0,
        entry.credit > 0 ? entry.credit : 0,
        entry.runningBalance,
        entry.currency,
        entry.statusOrCashbox,
        entry.actor
      ]),
      [
        'الإجمالي النهائي',
        'خلاصة كشف الحساب',
        `${statement.invoiceCount} فاتورة / ${statement.paymentCount} دفعة`,
        cust.name,
        statement.totalInvoiced,
        statement.totalPaid,
        statement.remainingDebt,
        statement.currency,
        statement.remainingDebt > 0 ? 'مديونية قائمة' : 'خالص الذمة المالية',
        ''
      ]
    ];

    ReportExporter.exportToCsv(`كشف_حساب_عميل_${safeCustName}`, headers, rows);
  },

  /**
   * Exports a specific customer's detailed chronological account statement to an official printable PDF/Print layout.
   */
  exportCustomerStatementToPrint: (statement: CustomerStatementResult) => {
    const cust = statement.customer;
    const safeName = escapeHtml(cust.name);
    const safePhone = escapeHtml(cust.phone || 'غير مسجل');
    const safeAddress = escapeHtml(cust.address || 'غير محدد');
    const safeNotes = escapeHtml(cust.notes || '—');
    const safeCurrency = escapeHtml(statement.currency);

    const html = `
      <!DOCTYPE html>
      <html dir="rtl" lang="ar">
      <head>
        <meta charset="UTF-8" />
        <title>كشف حساب العميل: ${safeName} - PMP PAINTS</title>
        <style>
          @import url('https://fonts.googleapis.com/css2?family=Cairo:wght@400;600;700;800&display=swap');
          body {
            font-family: 'Cairo', sans-serif;
            margin: 24px;
            color: #0f172a;
            background: #ffffff;
          }
          .header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            border-bottom: 3px solid #dc2626;
            padding-bottom: 14px;
            margin-bottom: 18px;
          }
          .brand h1 {
            margin: 0;
            color: #dc2626;
            font-size: 22px;
            font-weight: 800;
          }
          .brand p {
            margin: 4px 0 0;
            font-size: 12px;
            color: #475569;
          }
          .meta {
            text-align: left;
            font-size: 12px;
            color: #475569;
          }
          .customer-box {
            background: #f8fafc;
            border: 1px solid #cbd5e1;
            border-radius: 10px;
            padding: 14px 18px;
            margin-bottom: 16px;
            display: flex;
            justify-content: space-between;
            flex-wrap: wrap;
            gap: 12px;
          }
          .customer-box h2 {
            margin: 0 0 4px 0;
            font-size: 18px;
            color: #0f172a;
          }
          .customer-box .details {
            font-size: 12px;
            color: #475569;
          }
          .kpi-grid {
            display: grid;
            grid-template-columns: repeat(4, 1fr);
            gap: 12px;
            margin-bottom: 18px;
          }
          .kpi-card {
            border: 1px solid #cbd5e1;
            border-radius: 8px;
            padding: 10px 12px;
            background: #ffffff;
          }
          .kpi-card .label {
            font-size: 11px;
            color: #64748b;
            margin-bottom: 4px;
          }
          .kpi-card .val {
            font-size: 16px;
            font-weight: 800;
          }
          .val-debit { color: #b45309; }
          .val-credit { color: #059669; }
          .val-debt { color: #dc2626; }
          table {
            width: 100%;
            border-collapse: collapse;
            font-size: 12px;
          }
          th {
            background: #0f172a;
            color: #ffffff;
            padding: 9px 8px;
            text-align: right;
            border: 1px solid #cbd5e1;
          }
          td {
            padding: 8px;
            border: 1px solid #cbd5e1;
            color: #1e293b;
          }
          tr:nth-child(even) td {
            background: #f8fafc;
          }
          .total-row td {
            background: #f1f5f9 !important;
            font-weight: 800;
            border-top: 2px solid #0f172a;
          }
          .footer {
            margin-top: 28px;
            display: flex;
            justify-content: space-between;
            font-size: 11px;
            color: #64748b;
            border-top: 1px solid #e2e8f0;
            padding-top: 12px;
          }
        </style>
      </head>
      <body>
        <div class="header">
          <div class="brand">
            <h1>شركة PMP للبوهيات والدهانات — PMP PAINTS</h1>
            <p>كشف حساب عميل تفصيلي معتمد (الفواتير المسحوبة والدفعات المسددة)</p>
          </div>
          <div class="meta">
            <div>تاريخ الإصدار: ${escapeHtml(new Date().toLocaleString('ar-EG'))}</div>
            <div>حالة الذمة: ${statement.remainingDebt > 0 ? 'مديونية قائمة' : 'خالص الذمة المالية'}</div>
          </div>
        </div>

        <div class="customer-box">
          <div>
            <h2>كشف حساب العميل: ${safeName}</h2>
            <div class="details">
              رقم الهاتف: ${safePhone} &nbsp;|&nbsp;
              العنوان: ${safeAddress} &nbsp;|&nbsp;
              ملاحظات: ${safeNotes}
            </div>
          </div>
          <div class="details" style="text-align:left;">
            <div>عدد الفواتير المسجلة: <strong>${statement.invoiceCount}</strong></div>
            <div>عدد سندات السداد: <strong>${statement.paymentCount}</strong></div>
          </div>
        </div>

        <div class="kpi-grid">
          <div class="kpi-card">
            <div class="label">إجمالي الفواتير المسحوبة (مدين +)</div>
            <div class="val val-debit">${escapeHtml(statement.totalInvoiced.toLocaleString())} ${safeCurrency}</div>
          </div>
          <div class="kpi-card">
            <div class="label">إجمالي الدفعات المسددة (دائن -)</div>
            <div class="val val-credit">${escapeHtml(statement.totalPaid.toLocaleString())} ${safeCurrency}</div>
          </div>
          <div class="kpi-card">
            <div class="label">الرصيد المتبقي المستحق على العميل</div>
            <div class="val val-debt">${escapeHtml(statement.remainingDebt.toLocaleString())} ${safeCurrency}</div>
          </div>
          <div class="kpi-card">
            <div class="label">عدد الحركات المالية</div>
            <div class="val">${statement.entries.length} حركة</div>
          </div>
        </div>

        <table>
          <thead>
            <tr>
              <th>#</th>
              <th>التاريخ</th>
              <th>نوع الحركة</th>
              <th>المرجع / الفاتورة</th>
              <th>البيان والتفاصيل</th>
              <th>مدين (+)</th>
              <th>دائن (-)</th>
              <th>الرصيد التراكمي</th>
              <th>الحالة / الخزنة</th>
            </tr>
          </thead>
          <tbody>
            ${
              statement.entries.length === 0
                ? `<tr><td colspan="9" style="text-align:center;padding:18px;">لا توجد فواتير أو دفعات مسجلة لهذا العميل حتى الآن.</td></tr>`
                : statement.entries
                    .map(
                      (e, idx) => `
                  <tr>
                    <td>${idx + 1}</td>
                    <td>${escapeHtml(e.dateFormatted)}</td>
                    <td>${escapeHtml(e.typeLabel)}</td>
                    <td>${escapeHtml(e.referenceNo)}</td>
                    <td>${escapeHtml(e.description)}</td>
                    <td class="val-debit">${e.debit > 0 ? escapeHtml(e.debit.toLocaleString()) : '—'}</td>
                    <td class="val-credit">${e.credit > 0 ? escapeHtml(e.credit.toLocaleString()) : '—'}</td>
                    <td class="val-debt" style="font-weight:700;">${escapeHtml(e.runningBalance.toLocaleString())} ${escapeHtml(e.currency)}</td>
                    <td>${escapeHtml(e.statusOrCashbox)}</td>
                  </tr>
                `
                    )
                    .join('')
            }
            <tr class="total-row">
              <td colspan="5">الإجمالي الختامي لكشف حساب العميل (${safeName})</td>
              <td class="val-debit">${escapeHtml(statement.totalInvoiced.toLocaleString())} ${safeCurrency}</td>
              <td class="val-credit">${escapeHtml(statement.totalPaid.toLocaleString())} ${safeCurrency}</td>
              <td class="val-debt">${escapeHtml(statement.remainingDebt.toLocaleString())} ${safeCurrency}</td>
              <td>${statement.remainingDebt > 0 ? 'مستحق السداد' : 'مسدد بالكامل'}</td>
            </tr>
          </tbody>
        </table>

        <div class="footer">
          <div>توقيع المحاسب المختص / الإدارة المالية</div>
          <div>توقيع ومصادقة العميل: ${safeName}</div>
          <div>PMP PAINTS — مستند رسمي معتمد</div>
        </div>
        <script>
          window.onload = function() {
            setTimeout(function() { window.print(); }, 350);
          };
        </script>
      </body>
      </html>
    `;

    triggerPrintHtml(html, `كشف_حساب_عميل_${cust.name || 'عميل'}`);
  },

  /**
   * Exports a Treasury Cashbox Account Statement (كشف حساب الخزنة المالية) with running balances to official printable PDF layout.
   */
  exportTreasuryStatementToPrint: (params: {
    cashboxName: string;
    currency: string;
    closingBalance: number;
    movements: Array<{
      txDate?: string;
      reference?: string;
      direction?: string;
      amount?: number;
      currency?: string;
      description?: string;
      createdBy?: string;
      cashbox?: string;
    }>;
  }) => {
    const safeBox = escapeHtml(params.cashboxName);
    const safeCur = escapeHtml(params.currency);

    let totalIn = 0;
    let totalOut = 0;
    let running = 0;
    const ledgerRows = params.movements.map((m) => {
      const isIn = String(m.direction || '').trim() === 'وارد' || String(m.direction || '').trim().toUpperCase() === 'IN';
      const amt = Math.abs(Number(m.amount || 0));
      if (isIn) {
        totalIn += amt;
        running += amt;
      } else {
        totalOut += amt;
        running -= amt;
      }
      return {
        date: m.txDate || '-',
        ref: m.reference || '-',
        direction: isIn ? 'وارد (+)' : 'منصرف (-)',
        isIn,
        inAmt: isIn ? amt : 0,
        outAmt: !isIn ? amt : 0,
        runningBalance: running,
        description: m.description || '-',
        actor: m.createdBy || 'المدير',
        cashbox: m.cashbox || params.cashboxName
      };
    });

    const html = `
      <!DOCTYPE html>
      <html dir="rtl" lang="ar">
      <head>
        <meta charset="UTF-8" />
        <title>كشف حساب الخزنة: ${safeBox} (${safeCur}) - PMP PAINTS</title>
        <style>
          @import url('https://fonts.googleapis.com/css2?family=Cairo:wght@400;600;700;800&display=swap');
          body { font-family: 'Cairo', sans-serif; margin: 24px; color: #0f172a; background: #ffffff; }
          .header { display: flex; justify-content: space-between; align-items: center; border-bottom: 3px solid #dc2626; padding-bottom: 14px; margin-bottom: 18px; }
          .brand h1 { margin: 0; color: #dc2626; font-size: 22px; font-weight: 800; }
          .brand p { margin: 4px 0 0; font-size: 12px; color: #475569; }
          .meta { text-align: left; font-size: 12px; color: #475569; }
          .kpi-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 18px; }
          .kpi-card { border: 1px solid #cbd5e1; border-radius: 8px; padding: 10px 12px; background: #f8fafc; }
          .kpi-card .label { font-size: 11px; color: #64748b; margin-bottom: 4px; }
          .kpi-card .val { font-size: 15px; font-weight: 800; }
          .val-in { color: #059669; }
          .val-out { color: #dc2626; }
          .val-bal { color: #0f172a; }
          table { width: 100%; border-collapse: collapse; font-size: 11.5px; }
          th { background: #0f172a; color: #ffffff; padding: 9px 7px; text-align: right; border: 1px solid #cbd5e1; }
          td { padding: 7px; border: 1px solid #cbd5e1; color: #1e293b; }
          tr:nth-child(even) td { background: #f8fafc; }
          .total-row td { background: #f1f5f9 !important; font-weight: 800; border-top: 2px solid #0f172a; }
          .footer { margin-top: 28px; display: flex; justify-content: space-between; font-size: 11px; color: #64748b; border-top: 1px solid #e2e8f0; padding-top: 12px; }
        </style>
      </head>
      <body>
        <div class="header">
          <div class="brand">
            <h1>شركة PMP للبوهيات والدهانات — PMP PAINTS</h1>
            <p>كشف حساب تفصيلي للخزنة المالية: ${safeBox} (${safeCur})</p>
          </div>
          <div class="meta">
            <div>تاريخ الإصدار: ${escapeHtml(new Date().toLocaleString('ar-EG'))}</div>
            <div>عدد الحركات: ${ledgerRows.length}</div>
          </div>
        </div>

        <div class="kpi-grid">
          <div class="kpi-card">
            <div class="label">إجمالي الواردات (+)</div>
            <div class="val val-in">${escapeHtml(totalIn.toLocaleString())} ${safeCur}</div>
          </div>
          <div class="kpi-card">
            <div class="label">إجمالي المنصرفات (-)</div>
            <div class="val val-out">${escapeHtml(totalOut.toLocaleString())} ${safeCur}</div>
          </div>
          <div class="kpi-card">
            <div class="label">صافي حركة الفترة</div>
            <div class="val val-bal">${escapeHtml((totalIn - totalOut).toLocaleString())} ${safeCur}</div>
          </div>
          <div class="kpi-card">
            <div class="label">الرصيد الختامي بالخزنة</div>
            <div class="val val-in">${escapeHtml(Number(params.closingBalance || 0).toLocaleString())} ${safeCur}</div>
          </div>
        </div>

        <table>
          <thead>
            <tr>
              <th>#</th>
              <th>التاريخ</th>
              <th>رقم المستند</th>
              <th>الخزنة</th>
              <th>البيان والتفاصيل</th>
              <th>وارد (+)</th>
              <th>منصرف (-)</th>
              <th>الرصيد التراكمي</th>
              <th>المنفذ</th>
            </tr>
          </thead>
          <tbody>
            ${
              ledgerRows.length === 0
                ? `<tr><td colspan="9" style="text-align:center;padding:18px;">لا توجد حركات مالية مسجلة لهذه الخزنة حالياً.</td></tr>`
                : ledgerRows
                    .map(
                      (r, idx) => `
                  <tr>
                    <td>${idx + 1}</td>
                    <td>${escapeHtml(r.date)}</td>
                    <td>${escapeHtml(r.ref)}</td>
                    <td>${escapeHtml(r.cashbox)}</td>
                    <td>${escapeHtml(r.description)}</td>
                    <td class="val-in">${r.inAmt > 0 ? escapeHtml(r.inAmt.toLocaleString()) : '—'}</td>
                    <td class="val-out">${r.outAmt > 0 ? escapeHtml(r.outAmt.toLocaleString()) : '—'}</td>
                    <td style="font-weight:700;">${escapeHtml(r.runningBalance.toLocaleString())} ${safeCur}</td>
                    <td>${escapeHtml(r.actor)}</td>
                  </tr>
                `
                    )
                    .join('')
            }
            <tr class="total-row">
              <td colspan="5">الإجمالي الختامي لكشف حساب (${safeBox})</td>
              <td class="val-in">${escapeHtml(totalIn.toLocaleString())} ${safeCur}</td>
              <td class="val-out">${escapeHtml(totalOut.toLocaleString())} ${safeCur}</td>
              <td>${escapeHtml(Number(params.closingBalance || 0).toLocaleString())} ${safeCur}</td>
              <td>معتمد</td>
            </tr>
          </tbody>
        </table>

        <div class="footer">
          <div>أمين الخزنة / المحاسب المختص</div>
          <div>اعتماد الإدارة المالية — PMP PAINTS</div>
        </div>
        <script>
          window.onload = function() {
            setTimeout(function() { window.print(); }, 350);
          };
        </script>
      </body>
      </html>
    `;
    triggerPrintHtml(html, `كشف_حساب_خزنة_${params.cashboxName || 'الخزنة'}`);
  }
};

