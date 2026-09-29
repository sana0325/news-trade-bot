// Короткий сигнал нового сигналу через WebAudio (без файлів).
let ctx = null;

export function unlockAudio() {
  try {
    ctx ??= new (window.AudioContext || window.webkitAudioContext)();
    if (ctx.state === 'suspended') ctx.resume();
  } catch {
    ctx = null;
  }
}

export function playChime(direction) {
  if (!ctx || ctx.state !== 'running') return;
  const notes = direction === 'put' ? [880, 660] : [660, 880];
  const t0 = ctx.currentTime;
  notes.forEach((f, i) => {
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = 'sine';
    osc.frequency.value = f;
    const t = t0 + i * 0.14;
    gain.gain.setValueAtTime(0.0001, t);
    gain.gain.exponentialRampToValueAtTime(0.18, t + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.0001, t + 0.22);
    osc.connect(gain).connect(ctx.destination);
    osc.start(t);
    osc.stop(t + 0.24);
  });
}
