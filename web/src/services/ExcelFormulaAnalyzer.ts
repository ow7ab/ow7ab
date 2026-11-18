/**
 * PMP Chemical Lab — Zero-Bloat OOXML (.xlsx) Structural Analyzer, Formula Evaluator & Staged Importer.
 * Exact TypeScript counterpart to Android `LabExcelAnalyzer.kt`.
 *
 * Security & IP Guarantees:
 * - Zero third-party spreadsheet parsers (no vulnerable XLSX/POI packages), preventing XXE/prototype pollution.
 * - Preserves original formulas (<f>), cached values (<v>), sheet links, external workbook refs, and broken refs (#REF!, #NAME?).
 * - Never fabricates missing workbooks and never overwrites existing APPROVED formulations automatically.
 * - Stages every imported formulation strictly in `NEEDS_REVIEW` (`approvedForProduction = false`).
 */

import { FactoryItem, FormulationIngredient, LabFormulation } from '../types/erp';
import { calculatePvc } from './PvcEngine';
import { computeSha256Hex } from './LabSecurityService';

export interface AnalyzedExcelCell {
  ref: string;
  row: number;
  col: number;
  rawValue: string;
  formula: string;
  evaluatedValue: string;
  valueKind: 'STATIC_NUMBER' | 'STATIC_TEXT' | 'FORMULA' | 'CROSS_SHEET_LINK' | 'EXTERNAL_LINK' | 'ERROR';
  hasMismatch: boolean;
}

export interface AnalyzedExcelSheet {
  name: string;
  rowCount: number;
  formulaCount: number;
  crossSheetLinkCount: number;
  externalLinkCount: number;
  brokenRefCount: number;
  mismatchCount: number;
  formulaKind: 'CURRENT' | 'HISTORICAL_ARCHIVE' | 'REFERENCE_DATA';
  warnings: string[];
  cells: AnalyzedExcelCell[];
}

export interface ImportedFormulaCandidate {
  sheetName: string;
  formulaNo: string;
  productName: string;
  paintType: string;
  baseKind: string;
  approvalStatus: 'NEEDS_REVIEW';
  formulaKind: 'CURRENT' | 'HISTORICAL_ARCHIVE';
  ingredients: FormulationIngredient[];
  calculatedBatchKg: number;
  calculatedTotalCost: number;
  calculatedCostPerKg: number;
  excelReportedTotal?: number | null;
  mismatchDelta?: number | null;
  warnings: string[];
}

export interface ExcelWorkbookAnalysisReport {
  fileName: string;
  checksumSha256: string;
  sheets: AnalyzedExcelSheet[];
  candidates: ImportedFormulaCandidate[];
  materialsMatchedCount: number;
  materialsToCreateCount: number;
  externalLinksDetected: number;
  brokenRefsDetected: number;
  warningsCount: number;
  nonSecretSummary: {
    sheetNames: string[];
    formulaCount: number;
    historicalSheetsCount: number;
    referenceSheetsCount: number;
    externalLinksDetected: number;
    brokenRefsDetected: number;
  };
}

const EXCEL_ERROR_TOKENS = ['#REF!', '#NAME?', '#VALUE!', '#N/A', '#DIV/0!', '#NUM!', '#NULL!'];

function readUint16LE(buf: Uint8Array, offset: number): number {
  return buf[offset] | (buf[offset + 1] << 8);
}

function readUint32LE(buf: Uint8Array, offset: number): number {
  return (buf[offset] | (buf[offset + 1] << 8) | (buf[offset + 2] << 16) | (buf[offset + 3] << 24)) >>> 0;
}

async function inflateRawBytes(compressed: Uint8Array): Promise<Uint8Array> {
  if (typeof DecompressionStream !== 'undefined') {
    const stream = new Blob([compressed as BlobPart]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
    const ab = await new Response(stream).arrayBuffer();
    return new Uint8Array(ab);
  }
  const zlibMod: any = await import(/* @vite-ignore */ 'node:zlib');
  const out: Uint8Array = zlibMod.inflateRawSync(compressed);
  return new Uint8Array(out);
}

/**
 * Extracts entries from an uncompressed/deflated ZIP container (.xlsx) safely, guarding ZIP bombs (>40MB per entry).
 */
export async function extractXlsxZipEntries(zipBytes: Uint8Array): Promise<Map<string, string>> {
  const entries = new Map<string, string>();
  const decoder = new TextDecoder('utf-8');

  // Locate End of Central Directory (PK\x05\x06)
  let eocdOffset = -1;
  const searchStart = Math.max(0, zipBytes.length - 65557);
  for (let i = zipBytes.length - 22; i >= searchStart; i--) {
    if (zipBytes[i] === 0x50 && zipBytes[i + 1] === 0x4b && zipBytes[i + 2] === 0x05 && zipBytes[i + 3] === 0x06) {
      eocdOffset = i;
      break;
    }
  }
  if (eocdOffset < 0) {
    throw new Error('الملف ليس بصيغة .xlsx صالحة (ترويسة ZIP غير موجودة).');
  }

  const totalEntries = readUint16LE(zipBytes, eocdOffset + 10);
  let cdOffset = readUint32LE(zipBytes, eocdOffset + 16);

  for (let idx = 0; idx < totalEntries && cdOffset + 46 <= zipBytes.length; idx++) {
    if (zipBytes[cdOffset] !== 0x50 || zipBytes[cdOffset + 1] !== 0x4b || zipBytes[cdOffset + 2] !== 0x01 || zipBytes[cdOffset + 3] !== 0x02) {
      break;
    }
    const compressionMethod = readUint16LE(zipBytes, cdOffset + 10);
    const compressedSize = readUint32LE(zipBytes, cdOffset + 20);
    const uncompressedSize = readUint32LE(zipBytes, cdOffset + 24);
    const nameLen = readUint16LE(zipBytes, cdOffset + 28);
    const extraLen = readUint16LE(zipBytes, cdOffset + 30);
    const commentLen = readUint16LE(zipBytes, cdOffset + 32);
    const localHeaderOffset = readUint32LE(zipBytes, cdOffset + 42);

    const entryName = decoder.decode(zipBytes.subarray(cdOffset + 46, cdOffset + 46 + nameLen));
    cdOffset += 46 + nameLen + extraLen + commentLen;

    if (!entryName.endsWith('.xml') && !entryName.endsWith('.rels')) continue;
    if (entryName.includes('..') || uncompressedSize > 40 * 1024 * 1024 || compressedSize > 40 * 1024 * 1024) {
      throw new Error(`محتوى مرفوض أمنياً داخل الأرشيف: ${entryName}`);
    }

    if (localHeaderOffset + 30 > zipBytes.length) continue;
    const localNameLen = readUint16LE(zipBytes, localHeaderOffset + 26);
    const localExtraLen = readUint16LE(zipBytes, localHeaderOffset + 28);
    const dataStart = localHeaderOffset + 30 + localNameLen + localExtraLen;
    const dataEnd = Math.min(zipBytes.length, dataStart + compressedSize);
    const payload = zipBytes.subarray(dataStart, dataEnd);

    if (compressionMethod === 0) {
      entries.set(entryName, decoder.decode(payload));
    } else if (compressionMethod === 8) {
      const inflated = await inflateRawBytes(payload);
      entries.set(entryName, decoder.decode(inflated));
    }
  }
  return entries;
}

function unescapeXml(raw: string): string {
  return raw
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'");
}

function stripTagsAndDecode(xml: string): string {
  return unescapeXml(xml.replace(/<[^>]+>/g, ''));
}

function parseSharedStrings(xml?: string): string[] {
  if (!xml) return [];
  const res: string[] = [];
  const siRegex = /<si\b[^>]*>([\s\S]*?)<\/si>/g;
  let m: RegExpExecArray | null;
  while ((m = siRegex.exec(xml)) !== null) {
    res.push(stripTagsAndDecode(m[1]));
  }
  return res;
}

function parseWorkbookSheets(workbookXml?: string, relsXml?: string): Array<{ name: string; target: string }> {
  if (!workbookXml) return [];
  const relMap = new Map<string, string>();
  if (relsXml) {
    const relRegex = /<Relationship\b([^>]*)\/?>/g;
    let rm: RegExpExecArray | null;
    while ((rm = relRegex.exec(relsXml)) !== null) {
      const attrs = rm[1];
      const idMatch = attrs.match(/Id="([^"]+)"/);
      const targetMatch = attrs.match(/Target="([^"]+)"/);
      if (idMatch && targetMatch) {
        let tgt = targetMatch[1].replace(/^\/+/, '');
        if (!tgt.startsWith('xl/')) tgt = `xl/${tgt}`;
        relMap.set(idMatch[1], tgt);
      }
    }
  }

  const sheets: Array<{ name: string; target: string }> = [];
  const sheetRegex = /<sheet\b([^>]*)\/?>/g;
  let sm: RegExpExecArray | null;
  let seq = 1;
  while ((sm = sheetRegex.exec(workbookXml)) !== null) {
    const attrs = sm[1];
    const nameMatch = attrs.match(/name="([^"]+)"/);
    const ridMatch = attrs.match(/r:id="([^"]+)"/);
    const sheetName = nameMatch ? unescapeXml(nameMatch[1]) : `Sheet${seq}`;
    const target = (ridMatch && relMap.get(ridMatch[1])) || `xl/worksheets/sheet${seq}.xml`;
    sheets.push({ name: sheetName, target });
    seq++;
  }
  return sheets;
}

function colLettersToIndex(letters: string): number {
  let col = 0;
  for (let i = 0; i < letters.length; i++) {
    col = col * 26 + (letters.charCodeAt(i) - 64);
  }
  return Math.max(0, col - 1);
}

function parseCellRef(ref: string): { col: number; row: number } {
  const m = ref.toUpperCase().match(/^([A-Z]+)(\d+)$/);
  if (!m) return { col: 0, row: 0 };
  return {
    col: colLettersToIndex(m[1]),
    row: Math.max(0, parseInt(m[2], 10) - 1)
  };
}

function formatCellRef(col: number, row: number): string {
  let c = col + 1;
  let letters = '';
  while (c > 0) {
    const rem = (c - 1) % 26;
    letters = String.fromCharCode(65 + rem) + letters;
    c = Math.floor((c - 1) / 26);
  }
  return `${letters}${row + 1}`;
}

function tokenizeRange(aRef: string, bRef: string): string[] {
  const a = parseCellRef(aRef.replace(/\$/g, ''));
  const b = parseCellRef(bRef.replace(/\$/g, ''));
  const minR = Math.min(a.row, b.row);
  const maxR = Math.max(a.row, b.row);
  const minC = Math.min(a.col, b.col);
  const maxC = Math.max(a.col, b.col);
  const refs: string[] = [];
  for (let r = minR; r <= maxR && refs.length < 2000; r++) {
    for (let c = minC; c <= maxC && refs.length < 2000; c++) {
      refs.push(formatCellRef(c, r));
    }
  }
  return refs;
}

/**
 * Recursive-descent Excel formula evaluator supporting +, -, *, /, ^, (), comparisons,
 * cell references, cross-sheet lookup ('Sheet2'!A1), and SUM/AVERAGE/MIN/MAX/COUNT/ROUND/IF.
 */
function evaluateExcelFormula(
  exprRaw: string,
  sheetName: string,
  gridBySheet: Map<string, Map<string, AnalyzedExcelCell>>,
  visited: Set<string>
): number | string {
  const expr = exprRaw.trim().replace(/^=/, '');
  if (!expr) return 0;

  if (/\[\d+]/.test(expr) || /\.xlsx]/i.test(expr)) {
    return '#EXT_REF';
  }
  for (const errTok of EXCEL_ERROR_TOKENS) {
    if (expr.toUpperCase().includes(errTok)) return errTok;
  }

  let pos = 0;
  const peek = () => expr[pos] || '';
  const skipWs = () => {
    while (pos < expr.length && /\s/.test(expr[pos])) pos++;
  };

  const resolveSingleRef = (targetSheet: string, refStr: string): number => {
    const cleanRef = refStr.replace(/\$/g, '').toUpperCase();
    const visitKey = `${targetSheet}!${cleanRef}`;
    if (visited.has(visitKey)) return 0;
    const sheetMap = gridBySheet.get(targetSheet) || gridBySheet.get(sheetName);
    const cell = sheetMap?.get(cleanRef);
    if (!cell) return 0;
    if (cell.formula) {
      visited.add(visitKey);
      const val = evaluateExcelFormula(cell.formula, targetSheet, gridBySheet, visited);
      visited.delete(visitKey);
      return typeof val === 'number' ? val : parseFloat(String(val)) || 0;
    }
    const num = parseFloat(cell.rawValue);
    return Number.isFinite(num) ? num : 0;
  };

  const readRangeValues = (argToken: string): number[] => {
    let targetSheet = sheetName;
    let body = argToken.trim();
    const exclIdx = body.lastIndexOf('!');
    if (exclIdx >= 0) {
      targetSheet = body.slice(0, exclIdx).replace(/^'+|'+$/g, '');
      body = body.slice(exclIdx + 1);
    }
    if (body.includes(':')) {
      const [s, e] = body.split(':');
      return tokenizeRange(s, e).map((r) => resolveSingleRef(targetSheet, r));
    }
    return [];
  };

  const parseComparison = (): number => {
    let left = parseAddSub();
    skipWs();
    if (expr.slice(pos, pos + 2) === '>=') {
      pos += 2;
      return left >= parseAddSub() ? 1 : 0;
    }
    if (expr.slice(pos, pos + 2) === '<=') {
      pos += 2;
      return left <= parseAddSub() ? 1 : 0;
    }
    if (expr.slice(pos, pos + 2) === '<>') {
      pos += 2;
      return left !== parseAddSub() ? 1 : 0;
    }
    if (peek() === '>') {
      pos++;
      return left > parseAddSub() ? 1 : 0;
    }
    if (peek() === '<') {
      pos++;
      return left < parseAddSub() ? 1 : 0;
    }
    if (peek() === '=') {
      pos++;
      return Math.abs(left - parseAddSub()) < 1e-9 ? 1 : 0;
    }
    return left;
  };

  const parseAddSub = (): number => {
    let left = parseMulDiv();
    while (true) {
      skipWs();
      const ch = peek();
      if (ch === '+') {
        pos++;
        left += parseMulDiv();
      } else if (ch === '-') {
        pos++;
        left -= parseMulDiv();
      } else {
        break;
      }
    }
    return left;
  };

  const parseMulDiv = (): number => {
    let left = parsePow();
    while (true) {
      skipWs();
      const ch = peek();
      if (ch === '*') {
        pos++;
        left *= parsePow();
      } else if (ch === '/') {
        pos++;
        const right = parsePow();
        if (Math.abs(right) < 1e-12) throw new Error('#DIV/0!');
        left /= right;
      } else {
        break;
      }
    }
    return left;
  };

  const parsePow = (): number => {
    const left = parseUnary();
    skipWs();
    if (peek() === '^') {
      pos++;
      return Math.pow(left, parsePow());
    }
    return left;
  };

  const parseUnary = (): number => {
    skipWs();
    if (peek() === '-') {
      pos++;
      return -parseUnary();
    }
    if (peek() === '+') {
      pos++;
      return parseUnary();
    }
    return parsePrimary();
  };

  const parseFunctionCall = (fnName: string): number => {
    pos++; // consume '('
    const argsRaw: string[] = [];
    let depth = 1;
    let start = pos;
    let inQuote = false;
    while (pos < expr.length && depth > 0) {
      const ch = expr[pos];
      if (ch === "'" || ch === '"') inQuote = !inQuote;
      if (!inQuote) {
        if (ch === '(') depth++;
        else if (ch === ')') depth--;
        else if ((ch === ',' || ch === ';') && depth === 1) {
          argsRaw.push(expr.slice(start, pos).trim());
          start = pos + 1;
        }
      }
      pos++;
    }
    const lastArg = expr.slice(start, Math.max(start, pos - 1)).trim();
    if (lastArg) argsRaw.push(lastArg);

    const expandNums = (): number[] => {
      const out: number[] = [];
      for (const a of argsRaw) {
        if (a.includes(':') && !/[+\-*/()]/.test(a)) {
          out.push(...readRangeValues(a));
        } else {
          const val = evaluateExcelFormula(a, sheetName, gridBySheet, visited);
          if (typeof val === 'number' && Number.isFinite(val)) out.push(val);
        }
      }
      return out;
    };

    const upper = fnName.toUpperCase();
    if (upper === 'SUM') return expandNums().reduce((a, b) => a + b, 0);
    if (upper === 'AVERAGE') {
      const nums = expandNums();
      return nums.length ? nums.reduce((a, b) => a + b, 0) / nums.length : 0;
    }
    if (upper === 'MIN') {
      const nums = expandNums();
      return nums.length ? Math.min(...nums) : 0;
    }
    if (upper === 'MAX') {
      const nums = expandNums();
      return nums.length ? Math.max(...nums) : 0;
    }
    if (upper === 'COUNT') {
      return expandNums().length;
    }
    if (upper === 'ROUND') {
      const val = Number(evaluateExcelFormula(argsRaw[0] || '0', sheetName, gridBySheet, visited)) || 0;
      const digits = Number(evaluateExcelFormula(argsRaw[1] || '0', sheetName, gridBySheet, visited)) || 0;
      const factor = Math.pow(10, digits);
      return Math.round(val * factor) / factor;
    }
    if (upper === 'IF') {
      const cond = Number(evaluateExcelFormula(argsRaw[0] || '0', sheetName, gridBySheet, visited)) || 0;
      const branch = cond !== 0 ? argsRaw[1] || '0' : argsRaw[2] || '0';
      const val = evaluateExcelFormula(branch, sheetName, gridBySheet, visited);
      return typeof val === 'number' ? val : parseFloat(String(val)) || 0;
    }
    return 0;
  };

  const parsePrimary = (): number => {
    skipWs();
    if (peek() === '(') {
      pos++;
      const v = parseComparison();
      skipWs();
      if (peek() === ')') pos++;
      return v;
    }

    // Quoted sheet reference: 'Sheet Name'!A1
    if (peek() === "'") {
      const endQ = expr.indexOf("'", pos + 1);
      if (endQ > pos && expr[endQ + 1] === '!') {
        const targetSheet = expr.slice(pos + 1, endQ);
        pos = endQ + 2;
        const refM = expr.slice(pos).match(/^(\$?[A-Za-z]{1,3}\$?\d+)/);
        if (refM) {
          pos += refM[1].length;
          return resolveSingleRef(targetSheet, refM[1]);
        }
      }
    }

    // Number literal
    const numMatch = expr.slice(pos).match(/^(\d+(?:\.\d+)?)/);
    if (numMatch) {
      pos += numMatch[1].length;
      return parseFloat(numMatch[1]);
    }

    // Identifier: function or Sheet!Ref or CellRef
    const idMatch = expr.slice(pos).match(/^([A-Za-z0-9_.\u0600-\u06FF$]+)(![\$A-Za-z0-9]+)?/);
    if (idMatch) {
      const fullIdent = idMatch[0];
      pos += fullIdent.length;
      skipWs();
      if (peek() === '(') {
        return parseFunctionCall(fullIdent);
      }
      if (idMatch[2]) {
        return resolveSingleRef(idMatch[1], idMatch[2].slice(1));
      }
      return resolveSingleRef(sheetName, fullIdent);
    }

    pos++;
    return 0;
  };

  try {
    const res = parseComparison();
    return Number.isFinite(res) ? Math.round(res * 100000) / 100000 : '#VALUE!';
  } catch (err: any) {
    if (err?.message === '#DIV/0!') return '#DIV/0!';
    return '#VALUE!';
  }
}

function classifyChemicalType(name: string): string {
  const n = name.toLowerCase();
  if (n.includes('تيتان') || n.includes('tio2') || n.includes('أكسيد') || n.includes('صبغ') || n.includes('ملون')) {
    return 'صبغة / ملون';
  }
  if (n.includes('كربون') || n.includes('كالسيت') || n.includes('تالك') || n.includes('بارايت') || n.includes('caco3') || n.includes('talc')) {
    return 'مالئ وحبيبات';
  }
  if (n.includes('راتنج') || n.includes('أكريليك') || n.includes('acrylic') || n.includes('resin') || n.includes('فاينيل') || n.includes('رابط')) {
    return 'راتنج / بوليمر رابط';
  }
  if (n.includes('ماء') || n.includes('water') || n.includes('مذيب') || n.includes('تربنتين')) {
    return 'مذيب / ماء';
  }
  return 'إضافات ومساعدات';
}

function detectColumnRoles(headers: string[]) {
  let nameCol = -1;
  let codeCol = -1;
  let weightCol = -1;
  let costCol = -1;
  let densityCol = -1;
  let solidsCol = -1;

  headers.forEach((raw, idx) => {
    const h = raw.trim().toLowerCase();
    if (!h) return;
    if (nameCol === -1 && (h.includes('مادة') || h.includes('خام') || h.includes('الصنف') || h.includes('material') || h.includes('ingredient') || h.includes('item') || h.includes('name'))) {
      nameCol = idx;
    } else if (codeCol === -1 && (h.includes('كود') || h.includes('رمز') || h.includes('code') || h.includes('sku'))) {
      codeCol = idx;
    } else if (costCol === -1 && (h.includes('سعر') || h.includes('تكلف') || h.includes('price') || h.includes('cost'))) {
      costCol = idx;
    } else if (weightCol === -1 && (h.includes('كمية') || h.includes('وزن') || h.includes('نسبة') || h.includes('كجم') || h.includes('qty') || h.includes('weight') || h.includes('kg') || h.includes('%'))) {
      weightCol = idx;
    } else if (densityCol === -1 && (h.includes('كثافة') || h.includes('density'))) {
      densityCol = idx;
    } else if (solidsCol === -1 && (h.includes('جوامد') || h.includes('صلب') || h.includes('solid'))) {
      solidsCol = idx;
    }
  });
  return { nameCol, codeCol, weightCol, costCol, densityCol, solidsCol };
}

export async function analyzeXlsxWorkbook(
  fileName: string,
  bytes: Uint8Array,
  existingItems: FactoryItem[] = [],
  existingFormulations: LabFormulation[] = []
): Promise<ExcelWorkbookAnalysisReport> {
  const checksumSha256 = await computeSha256Hex(bytes);
  const entries = await extractXlsxZipEntries(bytes);
  const sharedStrings = parseSharedStrings(entries.get('xl/sharedStrings.xml'));
  const sheetDefs = parseWorkbookSheets(entries.get('xl/workbook.xml'), entries.get('xl/_rels/workbook.xml.rels'));

  const gridBySheet = new Map<string, Map<string, AnalyzedExcelCell>>();
  const rawSheetRows = new Map<string, Array<{ rowIdx: number; cols: Map<number, AnalyzedExcelCell> }>>();

  for (const sDef of sheetDefs) {
    const xml = entries.get(sDef.target) || entries.get(sDef.target.replace(/^xl\//, ''));
    const sheetMap = new Map<string, AnalyzedExcelCell>();
    const rowEntries: Array<{ rowIdx: number; cols: Map<number, AnalyzedExcelCell> }> = [];
    if (xml) {
      const cellRegex = /<c\b([^>]*)>([\s\S]*?)<\/c>/g;
      const rowsMap = new Map<number, Map<number, AnalyzedExcelCell>>();
      let cm: RegExpExecArray | null;
      while ((cm = cellRegex.exec(xml)) !== null) {
        const attrs = cm[1];
        const inner = cm[2];
        const refM = attrs.match(/r="([A-Z0-9]+)"/i);
        const typeM = attrs.match(/t="([^"]+)"/);
        const cellType = typeM ? typeM[1] : '';
        const ref = refM ? refM[1].toUpperCase() : 'A1';
        const { col, row } = parseCellRef(ref);

        const fM = inner.match(/<f[^>]*>([\s\S]*?)<\/f>/);
        const vM = inner.match(/<v[^>]*>([\s\S]*?)<\/v>/);
        const isM = inner.match(/<is>([\s\S]*?)<\/is>/);

        const formula = fM ? unescapeXml(fM[1]).trim() : '';
        let rawVal = '';
        if (cellType === 's' && vM) {
          const sIdx = parseInt(vM[1], 10);
          rawVal = sharedStrings[sIdx] ?? vM[1];
        } else if (cellType === 'inlineStr' && isM) {
          rawVal = stripTagsAndDecode(isM[1]);
        } else if (vM) {
          rawVal = unescapeXml(vM[1]).trim();
        }

        const isError = cellType === 'e' || EXCEL_ERROR_TOKENS.some((t) => rawVal.toUpperCase().includes(t) || formula.toUpperCase().includes(t));
        const isExternal = /\[\d+]/.test(formula) || /\.xlsx]/i.test(formula);
        const isCrossSheet = !isExternal && formula.includes('!');

        const cell: AnalyzedExcelCell = {
          ref,
          row,
          col,
          rawValue: rawVal,
          formula,
          evaluatedValue: rawVal,
          valueKind: isError
            ? 'ERROR'
            : isExternal
            ? 'EXTERNAL_LINK'
            : isCrossSheet
            ? 'CROSS_SHEET_LINK'
            : formula
            ? 'FORMULA'
            : Number.isFinite(Number(rawVal)) && rawVal !== ''
            ? 'STATIC_NUMBER'
            : 'STATIC_TEXT',
          hasMismatch: false
        };
        sheetMap.set(ref, cell);
        if (!rowsMap.has(row)) rowsMap.set(row, new Map());
        rowsMap.get(row)!.set(col, cell);
      }
      Array.from(rowsMap.keys())
        .sort((a, b) => a - b)
        .forEach((rIdx) => rowEntries.push({ rowIdx: rIdx, cols: rowsMap.get(rIdx)! }));
    }
    gridBySheet.set(sDef.name, sheetMap);
    rawSheetRows.set(sDef.name, rowEntries);
  }

  // Pass 2: evaluate formulas across sheets & verify mismatches
  const sheets: AnalyzedExcelSheet[] = [];
  const candidates: ImportedFormulaCandidate[] = [];
  let externalLinksDetected = 0;
  let brokenRefsDetected = 0;
  let warningsCount = 0;
  const matchedMaterialNames = new Set<string>();
  const newMaterialNames = new Set<string>();

  for (const sDef of sheetDefs) {
    const sMap = gridBySheet.get(sDef.name) || new Map();
    const rowList = rawSheetRows.get(sDef.name) || [];
    const warnings: string[] = [];
    let formulaCount = 0;
    let crossSheetLinkCount = 0;
    let externalLinkCount = 0;
    let brokenRefCount = 0;
    let mismatchCount = 0;

    for (const cell of sMap.values()) {
      if (cell.formula) {
        formulaCount++;
        const computed = evaluateExcelFormula(cell.formula, sDef.name, gridBySheet, new Set());
        cell.evaluatedValue = String(computed);
        if (computed === '#EXT_REF' || cell.valueKind === 'EXTERNAL_LINK') {
          externalLinkCount++;
          warnings.push(`مرجع مصنف خارجي غير محلول في ${cell.ref}: ${cell.formula}`);
        } else if (typeof computed === 'string' && computed.startsWith('#')) {
          brokenRefCount++;
          cell.valueKind = 'ERROR';
          warnings.push(`مرجع معطل (${computed}) في ${cell.ref}`);
        } else if (typeof computed === 'number' && cell.rawValue !== '') {
          const cachedNum = parseFloat(cell.rawValue);
          if (Number.isFinite(cachedNum) && Math.abs(cachedNum - computed) > 0.05) {
            cell.hasMismatch = true;
            mismatchCount++;
            warnings.push(`تباين حسابي في ${cell.ref}: المخزن=${cachedNum} vs المعاد حسابياً=${computed}`);
          }
        }
        if (cell.valueKind === 'CROSS_SHEET_LINK') crossSheetLinkCount++;
      } else if (cell.valueKind === 'ERROR') {
        brokenRefCount++;
        warnings.push(`قيمة خطأ في ${cell.ref}: ${cell.rawValue}`);
      }
    }

    const nameLower = sDef.name.toLowerCase();
    const isHistorical =
      nameLower.includes('قديم') || nameLower.includes('أرشيف') || nameLower.includes('old') || nameLower.includes('archive') || nameLower.includes('legacy');

    // Detect header row & candidate formula ingredients
    let headerRowPos = -1;
    let roles = detectColumnRoles([]);
    for (let i = 0; i < Math.min(rowList.length, 15); i++) {
      const rowCols = rowList[i].cols;
      const maxCol = Math.max(0, ...Array.from(rowCols.keys()));
      const headerTexts = Array.from({ length: maxCol + 1 }, (_, c) => rowCols.get(c)?.rawValue || '');
      const detected = detectColumnRoles(headerTexts);
      if (detected.nameCol >= 0 && (detected.weightCol >= 0 || detected.costCol >= 0)) {
        headerRowPos = i;
        roles = detected;
        break;
      }
    }

    let isReferenceSheet = false;
    const sheetIngredients: FormulationIngredient[] = [];
    let excelReportedTotal: number | null = null;

    if (headerRowPos >= 0 && roles.weightCol === -1) {
      // Price list / reference sheet without weight/quantity column -> keep as REFERENCE_DATA, NOT a 0-kg formula
      isReferenceSheet = true;
    } else if (headerRowPos >= 0) {
      for (let i = headerRowPos + 1; i < rowList.length; i++) {
        const cols = rowList[i].cols;
        const nameCell = cols.get(roles.nameCol);
        const matName = (nameCell?.evaluatedValue || nameCell?.rawValue || '').trim();
        if (!matName) continue;

        const matLower = matName.toLowerCase();
        if (matLower.includes('إجمالي') || matLower.includes('مجموع') || matLower === 'total') {
          if (roles.costCol >= 0) {
            const totalCell = cols.get(roles.costCol);
            const tVal = parseFloat(totalCell?.evaluatedValue || totalCell?.rawValue || '');
            if (Number.isFinite(tVal) && tVal > 0) excelReportedTotal = tVal;
          }
          continue;
        }

        const wCell = cols.get(roles.weightCol);
        const weightVal = parseFloat(wCell?.evaluatedValue || wCell?.rawValue || '0') || 0;
        if (weightVal <= 0) continue;

        const cCell = roles.costCol >= 0 ? cols.get(roles.costCol) : undefined;
        let unitCostVal = parseFloat(cCell?.evaluatedValue || cCell?.rawValue || '0') || 0;

        const codeCell = roles.codeCol >= 0 ? cols.get(roles.codeCol) : undefined;
        const itemCode = (codeCell?.rawValue || '').trim();

        const dCell = roles.densityCol >= 0 ? cols.get(roles.densityCol) : undefined;
        const densityVal = parseFloat(dCell?.evaluatedValue || dCell?.rawValue || '') || 1.0;

        const sCell = roles.solidsCol >= 0 ? cols.get(roles.solidsCol) : undefined;
        const solidsVal = parseFloat(sCell?.evaluatedValue || sCell?.rawValue || '');
        const resolvedSolids = Number.isFinite(solidsVal) ? solidsVal : matLower.includes('ماء') ? 0 : 100;

        const existingMatch = existingItems.find(
          (fi) =>
            (itemCode && (fi.itemCode || '').toLowerCase() === itemCode.toLowerCase()) ||
            fi.name.trim().toLowerCase() === matName.toLowerCase()
        );
        if (existingMatch) {
          matchedMaterialNames.add(matName.toLowerCase());
          if (unitCostVal <= 0 && Number(existingMatch.lastPurchasePrice || existingMatch.unitCostEgp || 0) > 0) {
            unitCostVal = Number(existingMatch.lastPurchasePrice || existingMatch.unitCostEgp || 0);
          }
        } else {
          newMaterialNames.add(matName.toLowerCase());
        }

        sheetIngredients.push({
          id: `imp-${sDef.name}-${i}`,
          materialId: existingMatch?.id ?? null,
          itemCode: itemCode || existingMatch?.itemCode || '',
          name: matName,
          type: classifyChemicalType(matName),
          weight: weightVal,
          density: densityVal,
          solidsPercent: resolvedSolids,
          unitCost: unitCostVal,
          notes: `مستورد من ورقة (${sDef.name})`
        });
      }
    }

    if (sheetIngredients.length === 0 || sheetIngredients.every((x) => x.weight <= 0)) {
      isReferenceSheet = true;
    }

    const sheetKind = isReferenceSheet ? 'REFERENCE_DATA' : isHistorical ? 'HISTORICAL_ARCHIVE' : 'CURRENT';
    externalLinksDetected += externalLinkCount;
    brokenRefsDetected += brokenRefCount;
    warningsCount += warnings.length;

    sheets.push({
      name: sDef.name,
      rowCount: rowList.length,
      formulaCount,
      crossSheetLinkCount,
      externalLinkCount,
      brokenRefCount,
      mismatchCount,
      formulaKind: sheetKind,
      warnings,
      cells: Array.from(sMap.values())
    });

    if (!isReferenceSheet && sheetIngredients.length > 0) {
      const pvc = calculatePvc(sheetIngredients);
      const baseCode = sDef.name.replace(/[^\w\u0600-\u06FF-]+/g, '-').slice(0, 18) || `IMP-${candidates.length + 1}`;
      const collisionExists = existingFormulations.some(
        (f) => f.formulaNo.toLowerCase() === `imp-${baseCode}`.toLowerCase() || f.productName.trim().toLowerCase() === sDef.name.trim().toLowerCase()
      );
      const mismatchDelta =
        excelReportedTotal !== null ? Math.round((pvc.totalCost - excelReportedTotal) * 100) / 100 : null;

      candidates.push({
        sheetName: sDef.name,
        formulaNo: collisionExists
          ? `IMP-${baseCode}-${String(Date.now()).slice(-4)}`
          : `IMP-${baseCode}`,
        productName: sDef.name,
        paintType: pvc.paintClassification.split('(')[0].trim(),
        baseKind: 'أبيض',
        approvalStatus: 'NEEDS_REVIEW',
        formulaKind: isHistorical ? 'HISTORICAL_ARCHIVE' : 'CURRENT',
        ingredients: sheetIngredients,
        calculatedBatchKg: pvc.totalBatchWeight,
        calculatedTotalCost: pvc.totalCost,
        calculatedCostPerKg: pvc.costPerKg,
        excelReportedTotal,
        mismatchDelta,
        warnings: [
          ...warnings,
          ...(collisionExists ? ['اسم/كود مطابق لتركيبة سابقة — تم التوجيه لإصدار مراجعة جديد بدون استبدال التركيبة المعتمدة.'] : [])
        ]
      });
    }
  }

  return {
    fileName,
    checksumSha256,
    sheets,
    candidates,
    materialsMatchedCount: matchedMaterialNames.size,
    materialsToCreateCount: newMaterialNames.size,
    externalLinksDetected,
    brokenRefsDetected,
    warningsCount,
    nonSecretSummary: {
      sheetNames: sheets.map((s) => s.name),
      formulaCount: candidates.length,
      historicalSheetsCount: sheets.filter((s) => s.formulaKind === 'HISTORICAL_ARCHIVE').length,
      referenceSheetsCount: sheets.filter((s) => s.formulaKind === 'REFERENCE_DATA').length,
      externalLinksDetected,
      brokenRefsDetected
    }
  };
}
