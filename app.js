/* Alay — asistente de voz con avatar animado.
 * Escucha una palabra de activación ("Oye Alay"), muestra un avatar a pantalla
 * completa, entiende lo que dices, responde en voz alta y mueve la boca al hablar.
 */
(() => {
  'use strict';

  // ---------- Utilidades ----------
  const $ = (s) => document.querySelector(s);
  const store = {
    get(k, d) { try { const v = localStorage.getItem('alay.' + k); return v === null ? d : v; } catch { return d; } },
    set(k, v) { try { localStorage.setItem('alay.' + k, v); } catch { /* sin almacenamiento */ } },
  };
  const normalize = (s) => s.toLowerCase()
    .normalize('NFD').replace(/[̀-ͯ]/g, '')
    .replace(/[¿?¡!.,;:"']/g, ' ').replace(/\s+/g, ' ').trim();
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  // ---------- Ajustes ----------
  const DEFAULT_WAKE = 'oye alay, hey alay, ok alay, hola alay, alay, alai';
  const settings = {
    wake: store.get('wake', DEFAULT_WAKE),
    name: store.get('name', 'Alay'),
    voice: store.get('voice', ''),
    key: store.get('key', ''),
  };
  let wakeRegexes = [];
  function buildWake() {
    // "oye alay" -> /o\s*y\s*e\s*a\s*l\s*a\s*y/  (tolera espacios que mete el reconocedor)
    wakeRegexes = settings.wake.split(',').map(normalize).filter(Boolean)
      .sort((a, b) => b.length - a.length)
      .map((w) => new RegExp('\\b' + w.replace(/\s/g, '').split('').map((c) => c.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('\\s*') + '\\b'));
    const first = settings.wake.split(',')[0].trim();
    document.querySelectorAll('.wake-label').forEach((el) => {
      el.textContent = first.charAt(0).toUpperCase() + first.slice(1);
    });
  }
  function findWake(text) {
    for (const re of wakeRegexes) {
      const m = re.exec(text);
      if (m) return { rest: text.slice(m.index + m[0].length).trim() };
    }
    return null;
  }

  // ---------- Elementos ----------
  const ui = {
    overlay: $('#overlay'), status: $('#status'), startBtn: $('#startBtn'), talkBtn: $('#talkBtn'),
    youSaid: $('#youSaid'), reply: $('#reply'), stateLabel: $('#stateLabel'),
    mouth: $('#mouth'), eyeL: $('#eyeL'), eyeR: $('#eyeR'),
  };

  // ---------- Estado ----------
  // off | wake (esperando palabra clave) | listening | thinking | speaking
  let state = 'off';
  let micOn = false;
  let followUpTimer = null;
  let wakeLock = null;
  const history = [];

  const LABELS = { listening: 'Escuchando…', thinking: 'Pensando…', speaking: '', wake: '' };
  function setState(s) {
    state = s;
    ui.overlay.dataset.state = s;
    ui.stateLabel.textContent = LABELS[s] || '';
    if (s === 'wake') ui.status.textContent = `Esperando “${settings.wake.split(',')[0].trim()}”…`;
  }

  function openOverlay() {
    ui.overlay.classList.add('open');
    ui.overlay.setAttribute('aria-hidden', 'false');
    ui.youSaid.textContent = '';
    ui.reply.textContent = '';
  }
  function closeOverlay() {
    clearTimeout(followUpTimer);
    speechSynthesis.cancel();
    ui.overlay.classList.remove('open');
    ui.overlay.setAttribute('aria-hidden', 'true');
    setState(micOn ? 'wake' : 'off');
    if (micOn) startRec();
  }

  // ---------- Reconocimiento de voz ----------
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  let rec = null;
  let recRunning = false;

  function makeRec() {
    const r = new SR();
    r.lang = 'es-ES';
    r.continuous = true;
    r.interimResults = true;
    r.maxAlternatives = 1;

    r.onstart = () => { recRunning = true; };
    r.onend = () => {
      recRunning = false;
      // Chrome corta el reconocimiento cada cierto tiempo: lo reiniciamos.
      if (micOn && (state === 'wake' || state === 'listening')) setTimeout(startRec, 250);
    };
    r.onerror = (e) => {
      if (e.error === 'not-allowed' || e.error === 'service-not-allowed') {
        micOn = false;
        setState('off');
        ui.status.textContent = 'Permiso de micrófono denegado.';
        ui.startBtn.hidden = false;
      }
    };
    r.onresult = (e) => {
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const res = e.results[i];
        const text = normalize(res[0].transcript);
        if (!text) continue;

        if (state === 'wake') {
          const hit = findWake(text);
          if (!hit) continue;
          // ¡Palabra clave detectada! Aparece el avatar.
          if (!ui.overlay.classList.contains('open')) { openOverlay(); chime(); }
          setState('listening');
          armListenTimeout(8000);
          if (res.isFinal && hit.rest.length > 1) handleCommand(hit.rest, res[0].transcript);
        } else if (state === 'listening') {
          // Si el comando llega junto a la palabra clave, quitamos la palabra clave.
          const hit = findWake(text);
          const cmd = hit ? hit.rest : text;
          ui.youSaid.textContent = res[0].transcript;
          armListenTimeout(6000);
          if (res.isFinal && cmd.length > 1) handleCommand(cmd, res[0].transcript);
        }
      }
    };
    return r;
  }

  function startRec() {
    if (!rec || recRunning) return;
    try { rec.start(); } catch { /* ya estaba iniciado */ }
  }
  function stopRec() {
    if (!rec) return;
    try { rec.abort(); } catch { /* nada */ }
  }

  function armListenTimeout(ms) {
    clearTimeout(followUpTimer);
    followUpTimer = setTimeout(() => {
      if (state === 'listening') closeOverlay();
    }, ms);
  }

  async function enableMic() {
    if (!SR) {
      ui.status.innerHTML = 'Tu navegador no soporta reconocimiento de voz. Usa <b>Chrome</b> o <b>Edge</b> (Android / PC) o Safari reciente.';
      return;
    }
    rec = rec || makeRec();
    micOn = true;
    ui.startBtn.hidden = true;
    ui.talkBtn.hidden = false;
    setState('wake');
    startRec();
    requestWakeLock();
  }

  async function requestWakeLock() {
    try { wakeLock = await navigator.wakeLock?.request('screen'); } catch { /* opcional */ }
  }
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && micOn) { requestWakeLock(); startRec(); }
  });

  // ---------- Cerebro: comandos locales + IA opcional ----------
  let busy = false;
  async function handleCommand(cmd, raw) {
    if (busy) return;
    busy = true;
    clearTimeout(followUpTimer);
    ui.youSaid.textContent = raw;
    setState('thinking');
    stopRec(); // que no se escuche a sí mismo

    let answer;
    try {
      answer = localAnswer(cmd);
      if (answer === undefined) answer = settings.key ? await askClaude(raw) : fallback(cmd);
    } catch (err) {
      console.error(err);
      answer = 'Uy, tuve un problema para responder. Revisa tu conexión o tu clave de API.';
    }

    if (answer === null) { busy = false; closeOverlay(); return; } // "adiós", "nada"…
    await speak(answer);
    busy = false;

    if (!ui.overlay.classList.contains('open')) return;
    // Conversación continua: sigue escuchando unos segundos por si quieres algo más.
    setState('listening');
    startRec();
    armListenTimeout(7000);
  }

  const JOKES = [
    '¿Qué le dice un bit al otro? Nos vemos en el bus.',
    '¿Por qué el libro de matemáticas estaba triste? Porque tenía demasiados problemas.',
    '¿Cuál es el colmo de un robot? Tener un corazón de lata y que se le oxide.',
    'Le pregunté a mi wifi si me quería. Me dijo: depende de dónde te sientes.',
  ];

  function openUrl(url) {
    const w = window.open(url, '_blank');
    if (!w) location.href = url;
  }

  // Devuelve texto, null (cerrar) o undefined (no lo entendí localmente).
  function localAnswer(c) {
    const has = (...w) => w.some((x) => c.includes(x));
    const now = new Date();

    if (/^(nada|adios|chao|hasta luego|cierra(te)?|olvidalo|cancela(r)?)$/.test(c)) return null;
    if (has('que hora')) return `Son las ${now.toLocaleTimeString('es-ES', { hour: 'numeric', minute: '2-digit' })}.`;
    if (has('que dia', 'que fecha', 'fecha de hoy')) return `Hoy es ${now.toLocaleDateString('es-ES', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })}.`;
    if (has('chiste')) return JOKES[Math.floor(Math.random() * JOKES.length)];
    if (has('como te llamas', 'quien eres')) return `Soy ${settings.name}, tu asistente de voz.`;
    if (/^(hola|buenas|buenos dias|buenas tardes|buenas noches)\b/.test(c)) return '¡Hola! ¿En qué te ayudo?';
    if (has('gracias')) return '¡De nada! Para eso estoy.';
    if (has('como estas')) return '¡Muy bien, gracias! Lista para ayudarte.';

    let m;
    if ((m = c.match(/(?:pon|reproduce|ponme)\s+(.+)/))) {
      const q = m[1].replace(/^(musica de|la cancion|una cancion de|un video de)\s+/, '');
      openUrl('https://www.youtube.com/results?search_query=' + encodeURIComponent(q));
      return `Buscando ${q} en YouTube.`;
    }
    if ((m = c.match(/^(?:busca|buscar|googlea)\s+(.+)/))) {
      openUrl('https://www.google.com/search?q=' + encodeURIComponent(m[1]));
      return `Buscando ${m[1]}.`;
    }
    if ((m = c.match(/abre\s+(youtube|google|whatsapp|maps|instagram|gmail)/))) {
      const urls = { youtube: 'https://youtube.com', google: 'https://google.com', whatsapp: 'https://web.whatsapp.com',
        maps: 'https://maps.google.com', instagram: 'https://instagram.com', gmail: 'https://mail.google.com' };
      openUrl(urls[m[1]]);
      return `Abriendo ${m[1]}.`;
    }
    if ((m = c.match(/temporizador de (\d+)\s*(segundo|minuto|hora)/))) {
      const n = +m[1];
      const ms = n * { segundo: 1e3, minuto: 6e4, hora: 36e5 }[m[2]];
      setTimeout(() => { openOverlay(); stopRec(); speak('¡Se acabó el tiempo!').then(closeOverlay); }, ms);
      return `Listo, temporizador de ${n} ${m[2]}${n === 1 ? '' : 's'}.`;
    }
    if ((m = c.match(/cuanto es (-?[\d.,]+)\s*(mas|menos|por|x|entre|dividido entre|dividido por)\s*(-?[\d.,]+)/))) {
      const a = parseFloat(m[1].replace(',', '.')), b = parseFloat(m[3].replace(',', '.'));
      const r = { mas: a + b, menos: a - b, por: a * b, x: a * b }[m[2]] ?? (b === 0 ? NaN : a / b);
      return Number.isFinite(r) ? `${m[1]} ${m[2]} ${m[3]} es ${+r.toFixed(4)}.` : 'No se puede dividir entre cero.';
    }
    return undefined;
  }

  function fallback() {
    return 'Todavía no sé hacer eso. Puedo decirte la hora, la fecha, contarte un chiste, buscar en internet o poner música. Si añades una clave de API en ajustes, podré responder a cualquier pregunta.';
  }

  async function askClaude(text) {
    history.push({ role: 'user', content: text });
    while (history.length > 12) history.shift();
    const res = await fetch('https://api.anthropic.com/v1/messages', {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'x-api-key': settings.key,
        'anthropic-version': '2023-06-01',
        'anthropic-dangerous-direct-browser-access': 'true',
      },
      body: JSON.stringify({
        model: 'claude-haiku-5-5',
        max_tokens: 400,
        system: `Eres ${settings.name}, un asistente de voz amable que habla español. Tus respuestas se leen en voz alta: sé breve (1 a 3 frases), natural y sin markdown, listas, emojis ni enlaces. Fecha y hora actual: ${new Date().toLocaleString('es-ES')}.`,
        messages: history,
      }),
    });
    if (!res.ok) { history.pop(); throw new Error('API ' + res.status + ': ' + await res.text()); }
    const data = await res.json();
    const out = data.content.filter((b) => b.type === 'text').map((b) => b.text).join(' ').trim();
    history.push({ role: 'assistant', content: out });
    return out;
  }

  // ---------- Voz y animación de la boca ----------
  let voices = [];
  function loadVoices() {
    voices = speechSynthesis.getVoices().filter((v) => v.lang.toLowerCase().startsWith('es'));
    const sel = $('#voiceSelect');
    sel.innerHTML = '<option value="">Automática</option>' +
      voices.map((v) => `<option value="${v.name}">${v.name} (${v.lang})</option>`).join('');
    sel.value = settings.voice;
  }
  if ('speechSynthesis' in window) {
    loadVoices();
    speechSynthesis.onvoiceschanged = loadVoices;
  }

  let mouthTarget = 0, mouthOpen = 0, speaking = false, wordPulse = 0;

  async function speak(text) {
    setState('speaking');
    ui.reply.textContent = text;
    if (!('speechSynthesis' in window)) { await sleep(1500 + text.length * 40); return; }
    speechSynthesis.cancel();
    // Partimos en frases: Chrome corta las locuciones largas.
    const parts = text.match(/[^.!?]+[.!?]*/g) || [text];
    const voice = voices.find((v) => v.name === settings.voice) ||
      voices.find((v) => /es[-_](ES|MX|US)/i.test(v.lang)) || voices[0];
    speaking = true;
    for (const p of parts) {
      if (!ui.overlay.classList.contains('open')) break;
      await new Promise((resolve) => {
        const u = new SpeechSynthesisUtterance(p.trim());
        u.lang = voice?.lang || 'es-ES';
        if (voice) u.voice = voice;
        u.rate = 1.03;
        u.pitch = 1.1;
        u.onboundary = () => { wordPulse = 1; };
        u.onend = u.onerror = resolve;
        speechSynthesis.speak(u);
        // Seguridad por si el navegador nunca dispara onend.
        setTimeout(resolve, 2000 + p.length * 110);
      });
    }
    speaking = false;
  }

  // Bucle de animación: boca (lip-sync aproximado) y parpadeo.
  let nextBlink = performance.now() + 2500;
  function animate(t) {
    if (speaking || speechSynthesis.speaking) {
      // Oscilación tipo sílabas + golpe en cada palabra.
      const syll = Math.abs(Math.sin(t / 70)) * 0.6 + Math.abs(Math.sin(t / 113)) * 0.4;
      mouthTarget = Math.min(1, 0.15 + syll * 0.65 + wordPulse * 0.4);
      wordPulse *= 0.9;
    } else if (state === 'listening') {
      mouthTarget = 0.08;
    } else {
      mouthTarget = 0;
    }
    mouthOpen += (mouthTarget - mouthOpen) * 0.35;
    ui.mouth.setAttribute('ry', (2 + mouthOpen * 14).toFixed(2));
    ui.mouth.setAttribute('rx', (14 - mouthOpen * 3).toFixed(2));

    // Parpadeo
    let eyeRy = 11;
    if (t > nextBlink) {
      const k = (t - nextBlink) / 140;
      if (k < 1) eyeRy = 11 * Math.abs(1 - 2 * k) + 1;
      else nextBlink = t + 2000 + Math.random() * 3500;
    }
    if (state === 'thinking') eyeRy = Math.min(eyeRy, 6);
    ui.eyeL.setAttribute('ry', eyeRy.toFixed(2));
    ui.eyeR.setAttribute('ry', eyeRy.toFixed(2));
    requestAnimationFrame(animate);
  }
  requestAnimationFrame(animate);

  // Pequeño sonido al activarse.
  let actx;
  function chime() {
    try {
      actx = actx || new (window.AudioContext || window.webkitAudioContext)();
      const o = actx.createOscillator(), g = actx.createGain();
      o.type = 'sine';
      o.frequency.setValueAtTime(660, actx.currentTime);
      o.frequency.exponentialRampToValueAtTime(1100, actx.currentTime + 0.12);
      g.gain.setValueAtTime(0.0001, actx.currentTime);
      g.gain.exponentialRampToValueAtTime(0.2, actx.currentTime + 0.02);
      g.gain.exponentialRampToValueAtTime(0.0001, actx.currentTime + 0.25);
      o.connect(g).connect(actx.destination);
      o.start(); o.stop(actx.currentTime + 0.3);
    } catch { /* sin audio */ }
  }

  // ---------- Eventos de la interfaz ----------
  ui.startBtn.addEventListener('click', () => { chime(); speechSynthesis.speak(new SpeechSynthesisUtterance('')); enableMic(); });
  ui.talkBtn.addEventListener('click', () => {
    openOverlay(); chime(); setState('listening'); startRec(); armListenTimeout(8000);
  });
  $('#closeBtn').addEventListener('click', closeOverlay);
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeOverlay(); });

  const dlg = $('#settings');
  $('#settingsBtn').addEventListener('click', () => {
    $('#wakeInput').value = settings.wake;
    $('#nameInput').value = settings.name;
    $('#keyInput').value = settings.key;
    loadVoices();
    dlg.showModal();
  });
  dlg.addEventListener('close', () => {
    if (dlg.returnValue !== 'save') return;
    settings.wake = $('#wakeInput').value.trim() || DEFAULT_WAKE;
    settings.name = $('#nameInput').value.trim() || 'Alay';
    settings.voice = $('#voiceSelect').value;
    settings.key = $('#keyInput').value.trim();
    for (const k of Object.keys(settings)) store.set(k, settings[k]);
    buildWake();
    if (state === 'wake') setState('wake');
  });

  buildWake();
  if ('serviceWorker' in navigator && location.protocol !== 'file:') {
    navigator.serviceWorker.register('sw.js').catch(() => {});
  }
})();
