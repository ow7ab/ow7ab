/**
 * Professional CSV and File Exporter for PMP PAINTS ERP
 * Generates formatted CSVs and printable HTML tables respecting Arabic UTF-8 BOM
 */

export const ReportExporter = {
  /**
   * Exports an array of objects to a UTF-8 encoded CSV file with BOM
   * to guarantee seamless display in Excel with Arabic text.
   */
  exportToCsv(filename: string, headers: string[], rows: (string | number)[][]): void {
    const csvContent = [
      headers.join(','),
      ...rows.map(row =>
        row
          .map(val => {
            const str = String(val ?? '');
            if (str.includes(',') || str.includes('"') || str.includes('\n')) {
              return `"${str.replace(/"/g, '""')}"`;
            }
            return str;
          })
          .join(',')
      )
    ].join('\r\n');

    // Add \uFEFF for Excel Arabic UTF-8 BOM support
    const blob = new Blob(['\uFEFF' + csvContent], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.setAttribute('href', url);
    link.setAttribute('download', `${filename}.csv`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  },

  /**
   * Triggers clean browser print dialog with styled enterprise header & table
   */
  exportToPrint(title: string, headers: string[], rows: (string | number)[][]): void {
    const printWindow = window.open('', '_blank');
    if (!printWindow) return;

    const html = `
      <!DOCTYPE html>
      <html dir="rtl" lang="ar">
        <head>
          <meta charset="utf-8" />
          <title>${title} - PMP PAINTS ERP</title>
          <style>
            body { font-family: 'Cairo', sans-serif, system-ui; padding: 24px; color: #1e293b; }
            .header { display: flex; justify-content: space-between; align-items: center; border-bottom: 2px solid #dc2626; padding-bottom: 14px; margin-bottom: 20px; }
            .company { font-size: 20px; font-weight: 900; color: #dc2626; }
            .subtitle { font-size: 12px; color: #64748b; }
            .title { font-size: 16px; font-weight: bold; margin-bottom: 12px; }
            table { width: 100%; border-collapse: collapse; margin-top: 10px; font-size: 12px; }
            th { background: #f1f5f9; padding: 10px; border: 1px solid #cbd5e1; text-align: right; font-weight: bold; }
            td { padding: 8px 10px; border: 1px solid #cbd5e1; }
            tr:nth-child(even) { background: #f8fafc; }
            .footer { margin-top: 30px; font-size: 11px; text-align: center; color: #94a3b8; border-top: 1px solid #e2e8f0; padding-top: 10px; }
          </style>
        </head>
        <body>
          <div class="header">
            <div>
              <div class="company">شركة PMP Paints للصناعات الكيماوية والطلاءات</div>
              <div class="subtitle">المنظومة السحابية المتكاملة لإدارة المصانع والمستودعات</div>
            </div>
            <div style="text-align: left; font-size: 11px; color: #64748b;">
              تاريخ الطباعة: ${new Date().toLocaleDateString('ar-EG')}<br/>
              السيرفر: PMP-ERP-V16
            </div>
          </div>
          <div class="title">${title}</div>
          <table>
            <thead>
              <tr>${headers.map(h => `<th>${h}</th>`).join('')}</tr>
            </thead>
            <tbody>
              ${rows.map(r => `<tr>${r.map(v => `<td>${v ?? ''}</td>`).join('')}</tr>`).join('')}
            </tbody>
          </table>
          <div class="footer">
            وثيقة معتمدة ومصدرة رسمياً من نظام PMP PAINTS ERP السحابي • سرية وتخص الاستخدام الداخلي فقط
          </div>
          <script>
            window.onload = function() { window.print(); }
          </script>
        </body>
      </html>
    `;

    printWindow.document.write(html);
    printWindow.document.close();
  }
};
