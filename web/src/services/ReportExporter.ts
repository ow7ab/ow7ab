import { CustomerStatementResult } from './ErpCalculations';

export const ReportExporter = {
  exportToCsv: (filename: string, headers: string[], rows: (string | number | undefined | null)[][]) => {
    const BOM = '\uFEFF';
    const csvContent =
      BOM +
      [
        headers.map((h) => `"${String(h).replace(/"/g, '""')}"`).join(','),
        ...rows.map((row) => row.map((cell) => `"${String(cell ?? '').replace(/"/g, '""')}"`).join(','))
      ].join('\n');

    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.setAttribute('href', url);
    link.setAttribute('download', `${filename}_${new Date().toISOString().slice(0, 10)}.csv`);
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

    const printWindow = window.open('', '_blank', 'width=1050,height=800');
    if (!printWindow) {
      window.print();
      return;
    }

    const html = `
      <!DOCTYPE html>
      <html dir="rtl" lang="ar">
      <head>
        <meta charset="UTF-8" />
        <title>${title} - PMP PAINTS ERP</title>
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
            <h1>شركة المهندس للدهانات — PMP PAINTS</h1>
            <p>المنظومة الصناعية والمحاسبية الموحدة (السودان • مصر)</p>
          </div>
          <div class="meta">
            <div>تاريخ الإصدار: ${new Date().toLocaleString('ar-EG')}</div>
            <div>المنظومة السحابية الموحدة المعتمدة</div>
          </div>
        </div>
        <div class="report-title">
          <h2>${title}</h2>
          <p>${subtitle}</p>
        </div>
        <table>
          <thead>
            <tr>
              ${headers.map((h) => `<th>${h}</th>`).join('')}
            </tr>
          </thead>
          <tbody>
            ${rows
              .map(
                (r) => `
              <tr>
                ${r.map((c) => `<td>${c ?? ''}</td>`).join('')}
              </tr>
            `
              )
              .join('')}
          </tbody>
        </table>
        <div class="footer">
          <div>اعتماد الإدارة المالية والمراجعة</div>
          <div>PMP PAINTS Enterprise ERP — مستند رسمي معتمد</div>
        </div>
        <script>
          window.onload = function() {
            setTimeout(function() { window.print(); }, 350);
          };
        </script>
      </body>
      </html>
    `;

    printWindow.document.open();
    printWindow.document.write(html);
    printWindow.document.close();
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
    const printWindow = window.open('', '_blank', 'width=1080,height=850');
    if (!printWindow) {
      window.print();
      return;
    }

    const html = `
      <!DOCTYPE html>
      <html dir="rtl" lang="ar">
      <head>
        <meta charset="UTF-8" />
        <title>كشف حساب العميل: ${cust.name} - PMP PAINTS ERP</title>
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
            <h1>شركة المهندس للدهانات — PMP PAINTS</h1>
            <p>كشف حساب عميل تفصيلي معتمد (الفواتير المسحوبة والدفعات المسددة)</p>
          </div>
          <div class="meta">
            <div>تاريخ الإصدار: ${new Date().toLocaleString('ar-EG')}</div>
            <div>حالة الذمة: ${statement.remainingDebt > 0 ? 'مديونية قائمة' : 'خالص الذمة المالية'}</div>
          </div>
        </div>

        <div class="customer-box">
          <div>
            <h2>كشف حساب العميل: ${cust.name}</h2>
            <div class="details">
              رقم الهاتف: ${cust.phone || 'غير مسجل'} &nbsp;|&nbsp;
              العنوان: ${cust.address || 'غير محدد'} &nbsp;|&nbsp;
              ملاحظات: ${cust.notes || '—'}
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
            <div class="val val-debit">${statement.totalInvoiced.toLocaleString()} ${statement.currency}</div>
          </div>
          <div class="kpi-card">
            <div class="label">إجمالي الدفعات المسددة (دائن -)</div>
            <div class="val val-credit">${statement.totalPaid.toLocaleString()} ${statement.currency}</div>
          </div>
          <div class="kpi-card">
            <div class="label">الرصيد المتبقي المستحق على العميل</div>
            <div class="val val-debt">${statement.remainingDebt.toLocaleString()} ${statement.currency}</div>
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
                    <td>${e.dateFormatted}</td>
                    <td>${e.typeLabel}</td>
                    <td>${e.referenceNo}</td>
                    <td>${e.description}</td>
                    <td class="val-debit">${e.debit > 0 ? e.debit.toLocaleString() : '—'}</td>
                    <td class="val-credit">${e.credit > 0 ? e.credit.toLocaleString() : '—'}</td>
                    <td class="val-debt" style="font-weight:700;">${e.runningBalance.toLocaleString()} ${e.currency}</td>
                    <td>${e.statusOrCashbox}</td>
                  </tr>
                `
                    )
                    .join('')
            }
            <tr class="total-row">
              <td colspan="5">الإجمالي الختامي لكشف حساب العميل (${cust.name})</td>
              <td class="val-debit">${statement.totalInvoiced.toLocaleString()} ${statement.currency}</td>
              <td class="val-credit">${statement.totalPaid.toLocaleString()} ${statement.currency}</td>
              <td class="val-debt">${statement.remainingDebt.toLocaleString()} ${statement.currency}</td>
              <td>${statement.remainingDebt > 0 ? 'مستحق السداد' : 'مسدد بالكامل'}</td>
            </tr>
          </tbody>
        </table>

        <div class="footer">
          <div>توقيع المحاسب المختص / الإدارة المالية</div>
          <div>توقيع ومصادقة العميل: ${cust.name}</div>
          <div>PMP PAINTS Enterprise ERP — مستند رسمي معتمد</div>
        </div>
        <script>
          window.onload = function() {
            setTimeout(function() { window.print(); }, 350);
          };
        </script>
      </body>
      </html>
    `;

    printWindow.document.open();
    printWindow.document.write(html);
    printWindow.document.close();
  }
};
