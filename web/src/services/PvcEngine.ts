/**
 * PMP Science & Chemistry Lab — Paint Engineering Calculation Engine
 * Exact TypeScript port of Android `com.example.util.PvcEngine` (`PvcEngine.kt`).
 */

import { FormulationIngredient } from '../types/erp';

export interface PvcCalculationResult {
  calculatedPvc: number;
  cpvcEstimated: number;
  totalSolidsWeight: number;
  totalSolidsVolume: number;
  totalBatchWeight: number;
  totalBatchVolume: number;
  calculatedDensity: number;
  totalCost: number;
  costPerKg: number;
  pigmentVolume: number;
  binderDryVolume: number;
  paintClassification: string;
  technicalAnalysisText: string;
  warnings: string[];
}

export interface PresetMaterial {
  name: string;
  type: string;
  defaultDensity: number;
  defaultSolidsPercent: number;
  defaultUnitCost: number;
  notes: string;
}

export const PRESET_MATERIALS: PresetMaterial[] = [
  { name: 'ثاني أكسيد التيتانيوم (Rutile TiO2)', type: 'صبغة / ملون', defaultDensity: 4.20, defaultSolidsPercent: 100.0, defaultUnitCost: 4.50, notes: 'صبغة بياض وتغطية أساسية' },
  { name: 'كربونات كالسيوم ميكرونايزد (CaCO3 5 Micron)', type: 'مالئ وحبيبات', defaultDensity: 2.70, defaultSolidsPercent: 100.0, defaultUnitCost: 0.40, notes: 'بودرة تعبئة وإطفاء لمعة' },
  { name: 'كربونات كالسيوم كالسيت (CaCO3 15 Micron)', type: 'مالئ وحبيبات', defaultDensity: 2.71, defaultSolidsPercent: 100.0, defaultUnitCost: 0.28, notes: 'مالئ اقتصادي' },
  { name: 'تالك مائي (Talc Magnesium Silicate)', type: 'مالئ وحبيبات', defaultDensity: 2.75, defaultSolidsPercent: 100.0, defaultUnitCost: 0.65, notes: 'مقاومة الترسيب والتشقق' },
  { name: 'بارايت كبريتات باريوم (Barium Sulfate)', type: 'مالئ وحبيبات', defaultDensity: 4.45, defaultSolidsPercent: 100.0, defaultUnitCost: 1.20, notes: 'كثافة ومقاومة كيميائية عالية' },
  { name: 'أكسيد حديد أحمر (Red Iron Oxide Fe2O3)', type: 'صبغة / ملون', defaultDensity: 5.10, defaultSolidsPercent: 100.0, defaultUnitCost: 3.20, notes: 'تلوين أحمر قوي ومقاوم للشمس' },
  { name: 'أكسيد حديد أصفر (Yellow Iron Oxide)', type: 'صبغة / ملون', defaultDensity: 4.05, defaultSolidsPercent: 100.0, defaultUnitCost: 3.40, notes: 'تلوين أصفر ثابت' },
  { name: 'كربون بلاك أسود (Carbon Black)', type: 'صبغة / ملون', defaultDensity: 1.80, defaultSolidsPercent: 100.0, defaultUnitCost: 4.80, notes: 'صبغة سواد داكن عالي التركيز' },
  { name: 'راتنج أكريليك نقي 50% (Pure Acrylic Emulsion)', type: 'راتنج / بوليمر رابط', defaultDensity: 1.06, defaultSolidsPercent: 50.0, defaultUnitCost: 2.80, notes: 'أعلى مقاومة جوية ولمعان' },
  { name: 'راتنج ستايرين أكريليك 50% (Styrene Acrylic)', type: 'راتنج / بوليمر رابط', defaultDensity: 1.04, defaultSolidsPercent: 50.0, defaultUnitCost: 2.10, notes: 'رابط مطفي اقتصادي متوازن' },
  { name: 'راتنج فاينيل أكريليك (Vinyl Acrylic VAM)', type: 'راتنج / بوليمر رابط', defaultDensity: 1.05, defaultSolidsPercent: 52.0, defaultUnitCost: 1.85, notes: 'دهانات داخلية ومعجون' },
  { name: 'ماء التشغيل (Process Water)', type: 'مذيب / ماء', defaultDensity: 1.00, defaultSolidsPercent: 0.0, defaultUnitCost: 0.05, notes: 'وسط التشتيت والمذيب الأساسي' },
  { name: 'تيكسانول (Texanol Coalescent)', type: 'إضافات ومساعدات', defaultDensity: 0.95, defaultSolidsPercent: 0.0, defaultUnitCost: 5.20, notes: 'مساعد تكوين الغشاء (MFFT)' },
  { name: 'مغلظ سليلوز (HEC Cellulose)', type: 'إضافات ومساعدات', defaultDensity: 1.00, defaultSolidsPercent: 25.0, defaultUnitCost: 6.50, notes: 'ضبط القوام واللزوجة' },
  { name: 'مشتت بيجمنت (Dispersant Agent)', type: 'إضافات ومساعدات', defaultDensity: 1.15, defaultSolidsPercent: 40.0, defaultUnitCost: 4.20, notes: 'تفتيت وتثبيت الأكاسيد' },
  { name: 'مانع رغوة (Defoamer PD-01)', type: 'إضافات ومساعدات', defaultDensity: 0.92, defaultSolidsPercent: 100.0, defaultUnitCost: 7.00, notes: 'كسر الفقاعات أثناء الخلط' },
  { name: 'مبيد حفظ العبوات (In-can Biocide)', type: 'إضافات ومساعدات', defaultDensity: 1.05, defaultSolidsPercent: 20.0, defaultUnitCost: 8.00, notes: 'حفظ مضاد للبكتيريا والفطريات' }
];

const round2 = (v: number): number => Math.round(v * 100) / 100;
const round3 = (v: number): number => Math.round(v * 1000) / 1000;

function evaluatePaintProperties(pvc: number, cpvc: number): [string, string] {
  if (pvc < 1.0) {
    return [
      'ورنيش شفاف خالي من الصبغات (Clear Varnish)',
      'الخلطة لا تحتوي على أصباغ أو ملوئات كافية. ستعطي فيلماً شفافاً عالي اللمعان والحماية، لكن بدون تغطية لونية.'
    ];
  }
  if (pvc < 25.0) {
    return [
      'دهان مائي لامع عالي الجودة (High Gloss Paint)',
      `نسبة الـ PVC منخفضة (${pvc}%) تضمن تغليفاً كاملاً للأصباغ بواسطة الراتنج، مما يمنح فيلماً مستمراً عالي اللمعان ومقاومة ممتازة للماء، وسهولة تامة في الغسيل ومسح الأوساخ.`
    ];
  }
  if (pvc < 38.0) {
    return [
      'دهان نصف لامع / حريري (Semi-Gloss / Satin)',
      'توازن مثالي بين اللمعان والصلابة وقوة التغطية. مناسب جداً للمطابخ والممرات والمناطق المعرضة للرطوبة والاحتكاك.'
    ];
  }
  if (pvc <= cpvc) {
    return [
      'دهان معماري مطفي (Architectural Matt Paint)',
      `نسبة الـ PVC (${pvc}%) قريبة من الحد الحرج (${cpvc}%). تغطية عالية ممتازة لإخفاء عيوب الجدران، مع مظهر مخملي ناعم غير عاكس، واحتفاظ كافٍ بقوة التماسك ومقاومة الفرك.`
    ];
  }
  return [
    'دهان فوق الحرج (Above CPVC / Primer & Undercoat)',
    `تجاوزت التركيبة تركيز الحجم الحرج للصبغة (PVC ${pvc}% > CPVC ${cpvc}%). الغشاء الناتج سيكون مسامياً وذا لمعان منعدم، مع امتصاص عالي وقوة تغطية جافة فورية. مثالي كطبقة أساس أولى (Primer) أو دهان أسقف اقتصادي.`
  ];
}

export function calculatePvc(ingredients: FormulationIngredient[]): PvcCalculationResult {
  const warnings: string[] = [];

  let totalBatchWeight = 0;
  let totalSolidsWeight = 0;
  let totalWetVolume = 0;
  let totalDryVolume = 0;
  let pigmentVolume = 0;
  let binderDryVolume = 0;
  let totalCost = 0;

  for (const item of ingredients) {
    const weight = Math.max(0, Number(item.weight) || 0);
    const rawDensity = Number(item.density) || 0;
    const density = rawDensity > 0.01 ? rawDensity : 1.0;
    const solidsPct = Math.min(100, Math.max(0, Number(item.solidsPercent) || 0));

    if (rawDensity <= 0.01 && weight > 0) {
      warnings.push(`المادة '${item.name}' تم إدخال كثافتها بصفر أو فارغة؛ تم اعتبار الكثافة 1.0 افتراضياً.`);
    }

    totalBatchWeight += weight;
    const dryWeight = weight * (solidsPct / 100.0);
    totalSolidsWeight += dryWeight;

    const wetVol = density > 0 ? weight / density : 0;
    totalWetVolume += wetVol;

    const dryVol = density > 0 ? dryWeight / density : 0;
    totalDryVolume += dryVol;

    totalCost += weight * (Number(item.unitCost) || 0);

    const typeLower = (item.type || '').trim().toLowerCase();
    const isPigmentOrExtender =
      typeLower.includes('صبغة') ||
      typeLower.includes('ملون') ||
      typeLower.includes('pigment') ||
      typeLower.includes('مالئ') ||
      typeLower.includes('حبيبات') ||
      typeLower.includes('extender');

    const isBinder =
      typeLower.includes('راتنج') ||
      typeLower.includes('بوليمر') ||
      typeLower.includes('رابط') ||
      typeLower.includes('binder') ||
      typeLower.includes('resin') ||
      typeLower.includes('emulsion');

    if (isPigmentOrExtender) {
      pigmentVolume += dryVol;
    } else if (isBinder) {
      binderDryVolume += dryVol;
    }
  }

  const totalPigmentAndBinderVol = pigmentVolume + binderDryVolume;
  const calculatedPvc =
    totalPigmentAndBinderVol > 0.0001 ? (pigmentVolume / totalPigmentAndBinderVol) * 100.0 : 0.0;

  const solidsWeightPercent =
    totalBatchWeight > 0.0001 ? (totalSolidsWeight / totalBatchWeight) * 100.0 : 0.0;

  const solidsVolumePercent =
    totalWetVolume > 0.0001 ? (totalDryVolume / totalWetVolume) * 100.0 : 0.0;

  const calculatedDensity = totalWetVolume > 0.0001 ? totalBatchWeight / totalWetVolume : 1.0;
  const costPerKg = totalBatchWeight > 0.0001 ? totalCost / totalBatchWeight : 0.0;

  const cpvcEstimated = 52.0;
  const roundedPvc = round2(calculatedPvc);
  const [paintClassification, technicalAnalysisText] = evaluatePaintProperties(roundedPvc, cpvcEstimated);

  return {
    calculatedPvc: roundedPvc,
    cpvcEstimated,
    totalSolidsWeight: round2(solidsWeightPercent),
    totalSolidsVolume: round2(solidsVolumePercent),
    totalBatchWeight: round2(totalBatchWeight),
    totalBatchVolume: round2(totalWetVolume),
    calculatedDensity: round3(calculatedDensity),
    totalCost: round2(totalCost),
    costPerKg: round3(costPerKg),
    pigmentVolume: round3(pigmentVolume),
    binderDryVolume: round3(binderDryVolume),
    paintClassification,
    technicalAnalysisText,
    warnings
  };
}

export function jsonToIngredients(json?: string | null): FormulationIngredient[] {
  if (!json || !json.trim()) return [];
  try {
    const parsed = JSON.parse(json);
    if (!Array.isArray(parsed)) return [];
    return parsed.map((o, idx) => ({
      id: String(o.id || `ing-${idx}-${Date.now()}`),
      name: String(o.name || 'مادة خام'),
      type: String(o.type || 'صبغة / ملون'),
      weight: Number(o.weight) || 0,
      density: Number(o.density) || 1.0,
      solidsPercent: o.solidsPercent !== undefined ? Number(o.solidsPercent) : 100.0,
      unitCost: Number(o.unitCost) || 0,
      notes: String(o.notes || '')
    }));
  } catch {
    return [];
  }
}

export function ingredientsToJson(list: FormulationIngredient[]): string {
  return JSON.stringify(list);
}
