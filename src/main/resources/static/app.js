const a = document.getElementById('nameA');
const b = document.getElementById('nameB');
const lab = document.getElementById('lab');
const btn = document.getElementById('compareBtn');
const drawer = document.getElementById('resultDrawer');
const evidence = document.getElementById('evidence');
const caseNumber = document.getElementById('caseNumber');
let caseCount = 0;

function updateCounts() {
  document.getElementById('countA').textContent = `${a.value.length} chars`;
  document.getElementById('countB').textContent = `${b.value.length} chars`;
}

function chars(el, targetId, other) {
  const target = document.getElementById(targetId);
  const x = el.value.trim();
  const y = other.value.trim().toLowerCase();
  target.innerHTML = [...x].map((ch, i) => {
    const changed = (y[i] || '') !== ch.toLowerCase();
    return `<span class="glyph ${changed ? 'changed' : ''}" style="transition-delay:${i * 45}ms">${escapeHtml(ch)}</span>`;
  }).join('');
}

function escapeHtml(value) {
  return value.replace(/[&<>'"]/g, ch => ({
    '&':'&amp;', '<':'&lt;', '>':'&gt;', "'":'&#39;', '"':'&quot;'
  }[ch]));
}

function labelBand(band) {
  return ({
    LIKELY_SAME: 'Likely same name',
    POSSIBLE: 'Possible match',
    UNLIKELY: 'Unlikely same name'
  })[band] || band;
}

function differenceLabel(type) {
  return type.replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, x => x.toUpperCase());
}

function render(result) {
  document.getElementById('status').textContent = labelBand(result.band);
  document.getElementById('pair').textContent = `${a.value || '—'} ↔ ${b.value || '—'}`;

  const mainReason = result.reasons[0] || 'The engine compared the spellings using deterministic transliteration rules.';
  document.getElementById('summary').textContent = mainReason;

  const items = result.reasons.slice(0, 4);
  evidence.innerHTML = items.map((reason, i) => {
    const width = Math.max(52, Math.round(result.score * 100) - (i * 5));
    return `
      <div class="evidence-row">
        <div class="evidence-num">${String(i + 1).padStart(2, '0')}</div>
        <div class="evidence-text">${escapeHtml(reason)}</div>
        <div class="evidence-meter"><span style="width:${width}%;animation-delay:${i * .08}s"></span></div>
      </div>`;
  }).join('');

  const frameSame = result.consonantFrameA === result.consonantFrameB;
  const conflict = result.differences.some(d => d.type === 'CONSONANT_CONFLICT');
  const vowel = result.differences.some(d => d.type === 'VOWEL_VARIATION');

  document.getElementById('m1').textContent = frameSame ? 'STRONG' : 'PARTIAL';
  document.getElementById('m2').textContent = vowel ? 'DETECTED' : 'LOW';
  document.getElementById('m3').textContent = conflict ? 'DETECTED' : 'NONE';
  document.getElementById('m4').textContent = result.band.replaceAll('_', ' ');

  const scoreEl = document.getElementById('score');
  const target = Math.round(result.score * 100);
  let n = 0;
  scoreEl.textContent = '0';
  const timer = setInterval(() => {
    n += Math.max(1, Math.ceil((target - n) / 8));
    if (n >= target) {
      n = target;
      clearInterval(timer);
    }
    scoreEl.textContent = n;
  }, 32);

  drawer.classList.add('show');
  lab.classList.add('analyzed');
}

function showError(message) {
  document.getElementById('status').textContent = 'Could not compare';
  document.getElementById('pair').textContent = `${a.value || '—'} ↔ ${b.value || '—'}`;
  document.getElementById('summary').textContent = message;
  document.getElementById('score').textContent = '—';
  evidence.innerHTML = '';
  drawer.classList.add('show');
}

async function compare() {
  if (!a.value.trim() || !b.value.trim()) return;

  caseCount += 1;
  caseNumber.textContent = String(caseCount).padStart(3, '0');

  drawer.classList.remove('show');
  lab.classList.remove('analyzed');
  lab.classList.add('scanning');
  btn.disabled = true;
  btn.querySelector('strong').textContent = 'Trace';
  btn.querySelector('small').textContent = 'reading';

  chars(a, 'glyphA', b);
  chars(b, 'glyphB', a);

  try {
    const response = await fetch('/api/match', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({nameA: a.value.trim(), nameB: b.value.trim()})
    });

    if (!response.ok) throw new Error('The matcher rejected this input. Try two non-empty Latin-script spellings.');
    const result = await response.json();
    render(result);
  } catch (error) {
    showError(error.message || 'The API is unavailable.');
  } finally {
    lab.classList.remove('scanning');
    btn.disabled = false;
    btn.querySelector('strong').textContent = 'Trace';
    btn.querySelector('small').textContent = 'compare';
  }
}

[a, b].forEach(el => {
  el.addEventListener('input', () => {
    updateCounts();
    drawer.classList.remove('show');
    lab.classList.remove('analyzed');
  });
  el.addEventListener('keydown', e => {
    if (e.key === 'Enter') compare();
  });
});

btn.addEventListener('click', compare);

document.querySelectorAll('.chip').forEach(chip => {
  chip.addEventListener('click', () => {
    a.value = chip.dataset.a;
    b.value = chip.dataset.b;
    updateCounts();
    compare();
  });
});

updateCounts();
setTimeout(compare, 350);
