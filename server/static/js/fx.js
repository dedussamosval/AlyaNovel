/** JavaScript - отвечает за анимацию сакуры, появления заднего фона,
    а также маскот Али с фразами  */
                

(() => {
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const mobile = innerWidth < 700;
  const raf2 = f => requestAnimationFrame(() => requestAnimationFrame(f));

  /* ── Фон: предзагрузка, потом плавное появление ── */
  if (document.body.classList.contains('bg')) {
    const src = mobile ? '/static/img/bg-m.webp' : '/static/img/bg.webp';
    const im = new Image();
    im.onload = im.onerror = () => raf2(() => document.body.classList.add('bg-ready'));
    im.src = src;
  }

  /* ── Лепестки ── */
  if (!reduce) {
    const cv = document.createElement('canvas');
    cv.id = 'fx'; document.body.prepend(cv);
    const ctx = cv.getContext('2d');
    const N = mobile ? 14 : 34;
    let W = 0, H = 0, dpr = 1, running = true, petals = [];

    const size = () => {
      dpr = Math.min(devicePixelRatio || 1, mobile ? 1.5 : 2);
      W = cv.width = Math.round(innerWidth * dpr);
      H = cv.height = Math.round(innerHeight * dpr);
    };
    let lastW = innerWidth, lastH = innerHeight;
    addEventListener('resize', () => {
      // на телефоне resize дёргается при скролле (адресная строка): игнорируем мелкие изменения высоты
      if (innerWidth === lastW && Math.abs(innerHeight - lastH) < 200) return;
      lastW = innerWidth; lastH = innerHeight; size();
    });

    const make = anywhere => ({
      x: Math.random() * W, y: anywhere ? Math.random() * H : -20 * dpr,
      r: (4 + Math.random() * 5) * dpr, vy: (.25 + Math.random() * .7) * dpr,
      d: Math.random() * 6.28, rot: Math.random() * 6.28,
      vr: (Math.random() - .5) * .03, o: .25 + Math.random() * .4,
    });
    const frame = () => {
      if (!running) return;
      ctx.clearRect(0, 0, W, H);
      for (const p of petals) {
        p.y += p.vy; p.d += .01; p.rot += p.vr; p.x += Math.sin(p.d) * .6 * dpr;
        if (p.y > H + 20) Object.assign(p, make(false));
        ctx.save(); ctx.translate(p.x, p.y); ctx.rotate(p.rot);
        ctx.globalAlpha = p.o; ctx.fillStyle = '#ffb7d5';
        ctx.beginPath(); ctx.ellipse(0, 0, p.r, p.r * .55, 0, 0, 6.28); ctx.fill();
        ctx.restore();
      }
      requestAnimationFrame(frame);
    };
    size(); petals = Array.from({ length: N }, () => make(true));
    document.addEventListener('visibilitychange', () => {
      running = !document.hidden; if (running) frame();
    });
    frame();
  }

  /* ── Маскот ── */
  const m = document.getElementById('mascot');
  if (m) {
    const img = m.querySelector('img'), bubble = m.querySelector('.m-bubble');
    let quotes = [];
    fetch(m.dataset.quotesUrl)
      .then(r => r.ok ? r.json() : [])
      .then(q => {
        quotes = Array.isArray(q) ? q.filter(s => typeof s === 'string' && s.trim()) : [];
        if (quotes.length) setTimeout(() => say(quotes[0]), 1500);
      })
      .catch(() => {});

    const show = () => { m.hidden = false; };
    img.addEventListener('error', () => m.remove());      // нет картинки: просто нет маскота, без битой иконки
    img.complete && img.naturalWidth ? show() : img.addEventListener('load', show);

    let timer, last = -1;
    const say = text => {
      bubble.textContent = text; bubble.classList.add('on');
      clearTimeout(timer); timer = setTimeout(() => bubble.classList.remove('on'), 3500);
    };
    m.addEventListener('click', () => {                   // click работает и для тапа
      if (!quotes.length) return;
      let i; do { i = Math.random() * quotes.length | 0; } while (quotes.length > 1 && i === last);
      say(quotes[last = i]);
    });

    // параллакс только там, где есть мышь; на телефоне гироскоп не трогаем (iOS просит разрешение)
    if (!reduce && matchMedia('(hover:hover) and (pointer:fine)').matches) {
      addEventListener('pointermove', e => {
        m.style.setProperty('--px', ((e.clientX / innerWidth - .5) * 12).toFixed(1) + 'px');
        m.style.setProperty('--py', ((e.clientY / innerHeight - .5) * 8).toFixed(1) + 'px');
      }, { passive: true });
    }
  }

  /* ── Появление блоков: только те, что ниже первого экрана (без мигания сверху) ── */
  if (!reduce && 'IntersectionObserver' in window) {
    const io = new IntersectionObserver(es => es.forEach(e => {
      if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); }
    }), { threshold: .1 });
    document.querySelectorAll('.card').forEach(el => {
      if (el.getBoundingClientRect().top > innerHeight) { el.classList.add('reveal'); io.observe(el); }
    });
  }
})();