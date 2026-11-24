/*
 * QAting 스니펫 (embed.js, 프로토콜 v1) — 배포 사이트에 한 줄 넣으면 QAting 실시간 QA 화면에서 핀이 요소에 붙어 따라간다.
 *
 *   <script src="https://acjb-qating.com/embed.js" async></script>
 *
 * - QAting 화면 안(iframe)에서 열렸을 때, 또는 QAting의 [사이트에서 QA 시작]으로 새 탭이 열렸을 때만 움직인다.
 *   일반 방문자에게는 아무것도 하지 않는다.
 * - 새 탭(오버레이, 2026-11-24): QAting 탭과 인사해 입장권을 받은 뒤에만 화면 오른쪽 아래에 QAting 툴바를 띄운다.
 *   사이트는 원래 주소 그대로라 로그인·기능이 실제로 동작하고, 툴바에서 요소를 골라 그 자리에서 이슈를 등록한다.
 * - 메시지는 이 스크립트를 내려준 출처(QAting)와만 주고받는다 — 다른 사이트가 이 페이지를 띄워도 아무것도 보내지 않는다.
 * - 보내는 것: 지금 경로, 화면에서 고른 요소의 CSS 경로·글자 앞부분·위치, 핀을 붙인 요소들의 지금 위치. 입력값·쿠키는 보내지 않는다.
 */
(function () {
  'use strict'
  if (window.__qatingEmbed) return
  window.__qatingEmbed = true

  var script = document.currentScript
  var QATING
  try {
    QATING = new URL(script && script.src ? script.src : '', location.href).origin
  } catch (e) {
    return
  }
  if (window.top === window) {
    overlayMode(QATING)
    return
  }
  var V = 1
  var parentWin = window.parent
  var connected = false
  var tracked = [] // [{key, selector, rx, ry}]
  var picking = false
  var highlight = null
  var hovered = null
  var lastPath = null
  var scheduled = false

  function send(type, data) {
    var msg = { source: 'qating-embed', v: V, type: type }
    for (var k in data) msg[k] = data[k]
    parentWin.postMessage(msg, QATING)
  }

  function currentPath() {
    return location.pathname + (location.hash && location.hash.indexOf('#/') === 0 ? location.hash : '')
  }

  function okId(id) {
    return /^[A-Za-z][\w-]*$/.test(id) && document.querySelectorAll('#' + id).length === 1
  }

  // 서버 캡처(PlaywrightPageCapturer)와 같은 규칙 — id가 있으면 거기서 끊고, 같은 태그 형제가 있으면 nth-of-type
  function selectorOf(el) {
    var parts = []
    for (var e = el; e && e.nodeType === 1 && e !== document.body && e !== document.documentElement && parts.length < 8; e = e.parentElement) {
      if (e.id && okId(e.id)) { parts.unshift('#' + e.id); break }
      var p = e.tagName.toLowerCase()
      var parent = e.parentElement
      if (parent) {
        var same = []
        for (var i = 0; i < parent.children.length; i++) if (parent.children[i].tagName === e.tagName) same.push(parent.children[i])
        if (same.length > 1) p += ':nth-of-type(' + (same.indexOf(e) + 1) + ')'
      }
      parts.unshift(p)
    }
    return parts.join(' > ').slice(0, 500)
  }

  function textOf(el) {
    var t = el.innerText || el.getAttribute('aria-label') || el.getAttribute('alt') || el.getAttribute('placeholder') || ''
    return t.replace(/\s+/g, ' ').trim().slice(0, 200)
  }

  function find(selector) {
    try { return document.querySelector(selector) } catch (e) { return null }
  }

  // 핀을 붙인 요소들의 지금 위치(화면 기준) — QAting이 iframe 위에 핀을 그린다
  function report() {
    scheduled = false
    if (!connected) return
    var vw = window.innerWidth
    var vh = window.innerHeight
    var pins = tracked.map(function (t) {
      var el = find(t.selector)
      if (!el) return { key: t.key, found: false }
      var r = el.getBoundingClientRect()
      if (r.width < 1 && r.height < 1) return { key: t.key, found: false }
      var x = r.left + t.rx * r.width
      var y = r.top + t.ry * r.height
      return { key: t.key, found: true, x: Math.round(x), y: Math.round(y), visible: x >= 0 && y >= 0 && x <= vw && y <= vh }
    })
    send('positions', { path: currentPath(), pins: pins, vw: vw, vh: vh })
  }

  function schedule() {
    if (scheduled) return
    scheduled = true
    window.requestAnimationFrame(report)
  }

  function checkPath() {
    var p = currentPath()
    if (p !== lastPath) {
      lastPath = p
      if (connected) send('path', { path: p })
      schedule()
    }
  }

  function setHighlight(el) {
    if (!highlight) {
      highlight = document.createElement('div')
      highlight.setAttribute('data-qating', 'highlight')
      highlight.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;border:2px solid #7c3aed;background:rgba(124,58,237,.08);border-radius:2px;transition:all .05s'
      document.documentElement.appendChild(highlight)
    }
    if (!el) { highlight.style.display = 'none'; return }
    var r = el.getBoundingClientRect()
    highlight.style.display = 'block'
    highlight.style.left = r.left + 'px'
    highlight.style.top = r.top + 'px'
    highlight.style.width = r.width + 'px'
    highlight.style.height = r.height + 'px'
  }

  function targetAt(e) {
    var el = document.elementFromPoint(e.clientX, e.clientY)
    if (!el || el === document.documentElement || el === document.body) return null
    return el
  }

  function onMove(e) {
    if (!picking) return
    hovered = targetAt(e)
    setHighlight(hovered)
  }

  // 고르는 동안 사이트의 클릭·제출은 막는다(버튼이 눌리지 않게)
  function block(e) {
    if (!picking) return
    e.preventDefault()
    e.stopPropagation()
  }

  function onClick(e) {
    if (!picking) return
    e.preventDefault()
    e.stopPropagation()
    var el = targetAt(e)
    if (!el) return
    var r = el.getBoundingClientRect()
    var sx = window.scrollX
    var sy = window.scrollY
    var rx = r.width > 0 ? Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)) : 0.5
    var ry = r.height > 0 ? Math.min(1, Math.max(0, (e.clientY - r.top) / r.height)) : 0.5
    stopPicking()
    send('picked', {
      path: currentPath(),
      point: { x: Math.round(e.clientX + sx), y: Math.round(e.clientY + sy) },
      client: { x: Math.round(e.clientX), y: Math.round(e.clientY) },
      ratio: { x: rx, y: ry },
      element: {
        selector: selectorOf(el),
        text: textOf(el),
        box: { x: Math.max(0, Math.round(r.left + sx)), y: Math.max(0, Math.round(r.top + sy)), width: Math.max(1, Math.round(r.width)), height: Math.max(1, Math.round(r.height)) },
      },
      viewport: { width: window.innerWidth, height: window.innerHeight },
    })
  }

  function onKey(e) {
    if (picking && e.key === 'Escape') { stopPicking(); send('pickcancel', {}) }
  }

  function startPicking() {
    picking = true
    document.documentElement.style.cursor = 'crosshair'
  }

  function stopPicking() {
    picking = false
    hovered = null
    setHighlight(null)
    document.documentElement.style.cursor = ''
  }

  window.addEventListener('message', function (e) {
    if (e.origin !== QATING || e.source !== parentWin) return
    var m = e.data
    if (!m || m.source !== 'qating' || typeof m.type !== 'string') return
    if (m.type === 'hello') {
      connected = true
      send('ready', { path: currentPath(), vw: window.innerWidth, vh: window.innerHeight })
      schedule()
    } else if (m.type === 'track' && Array.isArray(m.pins)) {
      tracked = m.pins.filter(function (p) { return p && typeof p.key === 'string' && typeof p.selector === 'string' })
        .map(function (p) { return { key: p.key, selector: p.selector, rx: +p.rx || 0, ry: +p.ry || 0 } })
      schedule()
    } else if (m.type === 'pick') {
      if (m.on) startPicking(); else stopPicking()
    } else if (m.type === 'scrollTo' && typeof m.selector === 'string') {
      var el = find(m.selector)
      if (el) el.scrollIntoView({ block: 'center', behavior: 'smooth' })
    }
  })

  window.addEventListener('scroll', schedule, true)
  window.addEventListener('resize', schedule)
  document.addEventListener('mousemove', onMove, true)
  document.addEventListener('click', onClick, true)
  document.addEventListener('mousedown', block, true)
  document.addEventListener('mouseup', block, true)
  document.addEventListener('submit', block, true)
  document.addEventListener('keydown', onKey, true)
  if (window.MutationObserver) {
    new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true, attributes: true, characterData: true })
  }
  setInterval(checkPath, 400)
  // 먼저 알린다 — QAting이 아직 듣지 않으면 QAting 쪽에서 hello를 보낸다
  send('announce', { path: currentPath() })
  checkPath()

  // ---------- 오버레이 모드(2026-11-24, docs/design-live-overlay.md) — 사이트 위에 QAting 툴바를 얹는다 ----------
  function overlayMode(QATING) {
    var KEY = 'qating.overlay'
    var API = QATING + '/api/v1/overlay'
    var STATUS = { needs_check: '확인 필요', in_progress: '수정 중', fixed: '수정 완료', needs_recheck: '다시 확인 필요', resolved: '해결' }
    var COLOR = { needs_check: '#e03131', in_progress: '#f08c00', fixed: '#2b8a3e', needs_recheck: '#e03131', resolved: '#868e96' }
    var session = null
    try {
      var saved = JSON.parse(sessionStorage.getItem(KEY) || 'null')
      if (saved && saved.exp * 1000 > Date.now()) session = saved
    } catch (e) { /* 저장소를 못 쓰면 매번 인사한다 */ }

    function pathNow() {
      return location.pathname + (location.hash && location.hash.indexOf('#/') === 0 ? location.hash : '')
    }

    // QAting 탭(window.opener)과 인사하고 입장권을 받는다 — 주소에 담지 않아 기록·리퍼러에 남지 않는다
    function handshake() {
      var tries = 0
      var timer = null
      function stop() { clearInterval(timer); window.removeEventListener('message', onMsg) }
      function onMsg(e) {
        if (e.origin !== QATING || e.source !== window.opener) return
        var m = e.data
        if (!m || m.source !== 'qating' || m.type !== 'overlay-token' || typeof m.token !== 'string') return
        stop()
        var exp = Date.parse(m.expires_at)
        session = { token: m.token, exp: isNaN(exp) ? Date.now() / 1000 + 4 * 3600 : exp / 1000, projectId: String(m.project_id || '') }
        try { sessionStorage.setItem(KEY, JSON.stringify(session)) } catch (err) { /* 이 탭에서만 쓰고 끝 */ }
        start()
      }
      function hello() {
        try { window.opener.postMessage({ source: 'qating-overlay', v: 1, type: 'overlay-hello', path: pathNow() }, QATING) } catch (e) { /* opener가 끊긴 경우 */ }
        if (++tries > 30) stop()
      }
      window.addEventListener('message', onMsg)
      timer = setInterval(hello, 1000)
      hello()
    }

    function api(path, opts) {
      opts = opts || {}
      var headers = { Authorization: 'Bearer ' + session.token }
      if (opts.json) headers['Content-Type'] = 'application/json'
      return fetch(API + path, { method: opts.method || 'GET', headers: headers, body: opts.json ? JSON.stringify(opts.json) : opts.body })
        .then(function (r) {
          return r.json().catch(function () { return {} }).then(function (b) {
            if (r.status === 401) { try { sessionStorage.removeItem(KEY) } catch (e) { /* 무시 */ } expired() }
            if (!r.ok) throw new Error((b && b.error && b.error.message) || ('요청 실패 ' + r.status))
            return b.data
          })
        })
    }

    var host, root, ui = {}, ctx = null, pins = [], showPins = true, picking = false, hovered = null, chosen = null
    var pasted = null, stream = null, lastPath = null, raf = false, busy = false

    function start() {
      host = document.createElement('div')
      host.setAttribute('data-qating', 'overlay')
      host.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:2147483647'
      root = host.attachShadow({ mode: 'closed' })
      root.innerHTML = '<style>' + CSS + '</style>' +
        '<div class="hl" hidden></div><div class="hl chosen" hidden></div><div class="layer"></div>' +
        '<div class="panel" hidden></div><div class="toast" hidden></div>' +
        '<div class="bar"><span class="logo">Q</span><span class="proj">QAting</span>' +
        '<button class="pick">핀 찍기</button><button class="pins on">핀 0</button><button class="min" title="접기">–</button></div>'
      ui.hl = root.querySelectorAll('.hl')[0]
      ui.chosen = root.querySelectorAll('.hl')[1]
      ui.layer = root.querySelector('.layer')
      ui.panel = root.querySelector('.panel')
      ui.toast = root.querySelector('.toast')
      ui.bar = root.querySelector('.bar')
      ui.proj = root.querySelector('.proj')
      ui.pick = root.querySelector('.pick')
      ui.pinsBtn = root.querySelector('.pins')
      ui.pick.addEventListener('click', function () { if (picking) stopPick(); else startPick() })
      ui.pinsBtn.addEventListener('click', function () { showPins = !showPins; ui.pinsBtn.classList.toggle('on', showPins); draw() })
      root.querySelector('.min').addEventListener('click', function () { ui.bar.classList.toggle('mini') })
      document.documentElement.appendChild(host)

      api('/context').then(function (c) { ctx = c; ui.proj.textContent = 'QAting · ' + (c.project_name || '') }).catch(function () { /* 툴바는 그대로 */ })
      document.addEventListener('mousemove', onMove, true)
      document.addEventListener('click', onClick, true)
      document.addEventListener('mousedown', block, true)
      document.addEventListener('mouseup', block, true)
      document.addEventListener('submit', block, true)
      document.addEventListener('keydown', onKey, true)
      window.addEventListener('scroll', schedule, true)
      window.addEventListener('resize', schedule)
      if (window.MutationObserver) new MutationObserver(schedule).observe(document.body || document.documentElement, { childList: true, subtree: true, attributes: true })
      window.addEventListener('pagehide', function () { if (stream) stream.getTracks().forEach(function (t) { t.stop() }) })
      setInterval(watchPath, 400)
      watchPath()
    }

    function expired() {
      if (!ui.bar) return
      ui.proj.textContent = '입장권이 만료됐어요 — QAting에서 [사이트에서 QA 시작]을 다시 눌러 주세요'
      ui.pick.disabled = true
    }

    function watchPath() {
      var p = pathNow()
      if (p === lastPath) return
      lastPath = p
      closeForm()
      loadPins()
    }

    function loadPins() {
      api('/pins?path=' + encodeURIComponent(lastPath)).then(function (d) {
        pins = (d && d.items) || []
        ui.pinsBtn.textContent = '핀 ' + pins.length
        draw()
      }).catch(function () { /* 다음 경로 변경 때 다시 */ })
    }

    function schedule() {
      if (raf) return
      raf = true
      window.requestAnimationFrame(function () { raf = false; draw() })
    }

    function find(selector) { try { return document.querySelector(selector) } catch (e) { return null } }

    // 핀은 요소 안의 점(rx·ry)으로 남아 있다 — 지금 화면에서 그 요소 위치로 다시 그린다
    function draw() {
      if (!ui.layer) return
      var html = ''
      if (showPins) {
        pins.forEach(function (p) {
          var el = find(p.selector)
          if (!el) return
          var r = el.getBoundingClientRect()
          if (r.width < 1 && r.height < 1) return
          var x = r.left + p.rx * r.width
          var y = r.top + p.ry * r.height
          html += '<button class="pin" data-id="' + esc(p.issue_id) + '" style="left:' + x + 'px;top:' + y + 'px;background:' +
            (COLOR[p.status] || '#7048e8') + '" title="' + esc('#' + p.pin_number + ' ' + p.title + ' · ' + (STATUS[p.status] || p.status)) + '">' + p.pin_number + '</button>'
        })
      }
      if (chosen) {
        var cr = chosen.el.getBoundingClientRect()
        html += '<span class="pin draft" style="left:' + (cr.left + chosen.rx * cr.width) + 'px;top:' + (cr.top + chosen.ry * cr.height) + 'px">+</span>'
        box(ui.chosen, cr)
      }
      ui.layer.innerHTML = html
      Array.prototype.forEach.call(ui.layer.querySelectorAll('.pin[data-id]'), function (b) {
        b.addEventListener('click', function () { openCard(b.getAttribute('data-id')) })
      })
    }

    function openCard(id) {
      var p = pins.filter(function (x) { return x.issue_id === id })[0]
      if (!p) return
      var link = session.projectId ? QATING + '/app/projects/' + session.projectId + '/issues/' + p.issue_id : QATING
      ui.panel.hidden = false
      ui.panel.innerHTML = '<div class="head"><b>#' + p.pin_number + ' ' + esc(p.title) + '</b><button class="x">×</button></div>' +
        '<div class="muted">' + esc(STATUS[p.status] || p.status) + '</div>' +
        '<a class="go" href="' + esc(link) + '" target="_blank" rel="noopener">QAting에서 보기</a>'
      ui.panel.querySelector('.x').addEventListener('click', closeForm)
    }

    function box(el, r) {
      el.hidden = false
      el.style.left = r.left + 'px'
      el.style.top = r.top + 'px'
      el.style.width = r.width + 'px'
      el.style.height = r.height + 'px'
    }

    function inOverlay(e) { return e.composedPath && e.composedPath().indexOf(host) >= 0 }

    function targetAt(e) {
      var el = document.elementFromPoint(e.clientX, e.clientY)
      if (!el || el === host || el === document.documentElement || el === document.body) return null
      return el
    }

    function startPick() {
      closeForm()
      picking = true
      ui.pick.textContent = '고르는 중 · Esc 취소'
      ui.pick.classList.add('active')
      document.documentElement.style.cursor = 'crosshair'
    }

    function stopPick() {
      picking = false
      hovered = null
      ui.hl.hidden = true
      ui.pick.textContent = '핀 찍기'
      ui.pick.classList.remove('active')
      document.documentElement.style.cursor = ''
    }

    function onMove(e) {
      if (!picking || inOverlay(e)) return
      hovered = targetAt(e)
      if (hovered) box(ui.hl, hovered.getBoundingClientRect()); else ui.hl.hidden = true
    }

    // 고르는 동안 사이트의 클릭·제출은 막는다(버튼이 눌리지 않게) — 툴바 클릭은 그대로
    function block(e) {
      if (!picking || inOverlay(e)) return
      e.preventDefault()
      e.stopPropagation()
    }

    function onKey(e) {
      if (e.key !== 'Escape') return
      if (picking) stopPick()
      else if (!ui.panel.hidden) closeForm()
    }

    function onClick(e) {
      if (!picking || inOverlay(e)) return
      e.preventDefault()
      e.stopPropagation()
      var el = targetAt(e)
      if (!el) return
      var r = el.getBoundingClientRect()
      var sx = window.scrollX
      var sy = window.scrollY
      stopPick()
      chosen = {
        el: el,
        rx: r.width > 0 ? Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)) : 0.5,
        ry: r.height > 0 ? Math.min(1, Math.max(0, (e.clientY - r.top) / r.height)) : 0.5,
        path: pathNow(),
        point: { x_px: Math.round(e.clientX + sx), y_px: Math.round(e.clientY + sy) },
        element: {
          selector: selectorOf(el),
          text: textOf(el),
          box: { x: Math.max(0, Math.round(r.left + sx)), y: Math.max(0, Math.round(r.top + sy)), width: Math.max(1, Math.round(r.width)), height: Math.max(1, Math.round(r.height)) },
        },
        viewport: { width: window.innerWidth, height: window.innerHeight },
      }
      openForm()
    }

    function openForm() {
      pasted = null
      var label = chosen.element.text ? '「' + chosen.element.text.slice(0, 30) + '」 요소' : chosen.element.selector
      var canShare = !!(navigator.mediaDevices && navigator.mediaDevices.getDisplayMedia)
      ui.panel.hidden = false
      ui.panel.innerHTML =
        '<div class="head"><b>이슈 등록</b><button class="x" type="button">×</button></div>' +
        '<div class="muted">' + esc(label) + ' · ' + esc(chosen.path) + '</div>' +
        '<label>제목<input name="title" maxlength="100" placeholder="예: 결제 버튼 색이 시안과 달라요"></label>' +
        '<label>무엇이 문제인가요<textarea name="description" rows="3" maxlength="2000" placeholder="보이는 현상을 적어 주세요"></textarea></label>' +
        '<label>기대 결과 <span class="muted">(선택)</span><textarea name="expected" rows="2" maxlength="2000"></textarea></label>' +
        '<label>심각도 <span class="muted">(선택)</span><select name="severity"><option value="">선택 안 함</option><option value="critical">치명</option><option value="high">높음</option><option value="medium">보통</option><option value="low">낮음</option></select></label>' +
        '<label>재현 절차 추가 <span class="muted">(선택, 한 줄에 하나)</span><textarea name="steps" rows="2" maxlength="2000" placeholder="로그인한다&#10;장바구니에 담는다"></textarea></label>' +
        '<label class="row"><input type="checkbox" name="share"' + (canShare ? ' checked' : ' disabled') + '> 지금 화면을 캡처해 붙이기' +
        (canShare ? ' <span class="muted">(브라우저가 이 탭 공유를 물어요)</span>' : ' <span class="muted">(이 브라우저는 지원 안 함)</span>') + '</label>' +
        '<div class="paste muted">또는 스크린샷을 여기에 붙여넣기(Ctrl/⌘+V)</div>' +
        '<div class="err" hidden></div>' +
        '<div class="actions"><button class="cancel" type="button">취소</button><button class="submit" type="button">등록</button></div>'
      ui.panel.querySelector('.x').addEventListener('click', closeForm)
      ui.panel.querySelector('.cancel').addEventListener('click', closeForm)
      ui.panel.querySelector('.submit').addEventListener('click', submit)
      ui.panel.onpaste = onPaste
      ui.panel.querySelector('input[name=title]').focus()
      draw()
    }

    function onPaste(e) {
      var items = (e.clipboardData && e.clipboardData.items) || []
      for (var i = 0; i < items.length; i++) {
        if (items[i].type.indexOf('image/') === 0) {
          pasted = items[i].getAsFile()
          var tip = ui.panel.querySelector('.paste')
          if (tip) tip.textContent = '붙여 넣은 이미지를 캡처로 씁니다(' + Math.round(pasted.size / 1024) + 'KB)'
          e.preventDefault()
          return
        }
      }
    }

    function closeForm() {
      chosen = null
      pasted = null
      if (ui.panel) { ui.panel.hidden = true; ui.panel.innerHTML = '' }
      if (ui.chosen) ui.chosen.hidden = true
      draw()
    }

    function field(name) { var el = ui.panel.querySelector('[name=' + name + ']'); return el ? el.value.trim() : '' }

    function submit() {
      if (busy || !chosen) return
      var err = ui.panel.querySelector('.err')
      var title = field('title')
      var description = field('description')
      if (!title || !description) {
        err.hidden = false
        err.textContent = !title ? '제목을 적어 주세요' : '무엇이 문제인지 적어 주세요'
        return
      }
      err.hidden = true
      busy = true
      var btn = ui.panel.querySelector('.submit')
      btn.disabled = true
      btn.textContent = '등록하는 중…'
      var share = ui.panel.querySelector('input[name=share]')
      var want = share && share.checked && !share.disabled
      var draft = chosen
      var steps = field('steps').split('\n').map(function (s) { return s.trim() }).filter(Boolean)
      var frame = null
      var links = (ctx && ctx.screen_links && ctx.screen_links.items) || []
      var same = links.filter(function (l) { return l.page_path === draft.path })
      if (same.length === 1) frame = same[0].frame_id
      var shot = pasted ? Promise.resolve(pasted) : want ? grabTab() : Promise.resolve(null)
      shot.then(function (blob) { return blob ? upload(blob) : { status: want ? 'failed' : 'none' } }, function () { return { status: 'none' } })
        .then(function (cap) {
          var body = {
            client_request_id: 'ov-' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8),
            title: title, description: description, page_path: draft.path,
            anchor: draft.point, viewport: draft.viewport, element: draft.element,
            expected_result: field('expected') || null, severity: field('severity') || null,
            repro_steps: steps.length ? steps : null, figma_frame_id: frame,
          }
          if (cap.id) body.capture_id = cap.id; else body.capture_status = cap.status
          return api('/manual-qa', { method: 'POST', json: body })
        })
        .then(function (issue) {
          busy = false
          closeForm()
          var n = issue && issue.manual_qa ? '#' + issue.manual_qa.pin_number + ' ' : ''
          toast(n + '등록했어요')
          try { if (window.opener) window.opener.postMessage({ source: 'qating-overlay', v: 1, type: 'overlay-registered', issue_id: issue && issue.id }, QATING) } catch (e) { /* QAting 탭이 닫혔으면 다음에 열 때 다시 읽는다 */ }
          loadPins()
        }, function (e) {
          busy = false
          btn.disabled = false
          btn.textContent = '등록'
          err.hidden = false
          err.textContent = e.message || '등록하지 못했어요'
        })
    }

    function upload(blob) {
      var form = new FormData()
      form.append('file', blob, blob.type === 'image/jpeg' ? 'capture.jpg' : 'capture.png')
      return api('/captures/upload', { method: 'POST', body: form }).then(function (c) { return { id: c.id } }, function () { return { status: 'failed' } })
    }

    // 로그인한 화면은 서버가 다시 찍을 수 없다 — 이 탭 화면 공유로 한 장 찍는다. 스트림은 탭에 두어 다음 등록부터는 묻지 않는다
    function grabTab() {
      var get = stream && stream.getVideoTracks()[0] && stream.getVideoTracks()[0].readyState === 'live' ? Promise.resolve(stream)
        : navigator.mediaDevices.getDisplayMedia({ video: { displaySurface: 'browser' }, audio: false, preferCurrentTab: true, selfBrowserSurface: 'include' })
      return get.then(function (s) {
        stream = s
        // 고른 요소의 테두리와 핀(+)은 캡처에 남기고 폼·툴바만 숨긴다 — 캡처만 봐도 어디인지 보이게
        if (chosen && chosen.el && chosen.el.scrollIntoView) chosen.el.scrollIntoView({ block: 'center' })
        ui.panel.style.visibility = 'hidden'
        ui.bar.style.visibility = 'hidden'
        draw()
        var video = document.createElement('video')
        video.muted = true
        video.srcObject = s
        return video.play().then(function () { return wait(250) }).then(function () {
          var c = document.createElement('canvas')
          c.width = video.videoWidth
          c.height = video.videoHeight
          c.getContext('2d').drawImage(video, 0, 0)
          ui.panel.style.visibility = ''
          ui.bar.style.visibility = ''
          return new Promise(function (res) {
            c.toBlob(function (png) {
              if (png && png.size <= 4.5 * 1024 * 1024) res(png)
              else c.toBlob(function (jpg) { res(jpg) }, 'image/jpeg', 0.85)
            }, 'image/png')
          })
        })
      }).catch(function (e) { ui.panel.style.visibility = ''; ui.bar.style.visibility = ''; throw e })
    }

    function wait(ms) { return new Promise(function (r) { setTimeout(r, ms) }) }

    function toast(text) {
      ui.toast.hidden = false
      ui.toast.textContent = text
      setTimeout(function () { ui.toast.hidden = true }, 2500)
    }

    function esc(v) {
      return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c] })
    }

    var CSS = [
      ':host{all:initial}',
      '*{box-sizing:border-box;font-family:-apple-system,BlinkMacSystemFont,"Pretendard","Apple SD Gothic Neo",sans-serif}',
      '.bar{position:fixed;right:16px;bottom:16px;display:flex;align-items:center;gap:6px;padding:6px 6px 6px 8px;background:#1f2a44;color:#fff;border-radius:999px;box-shadow:0 6px 20px rgba(0,0,0,.25);pointer-events:auto;font-size:13px}',
      '.bar.mini .proj,.bar.mini .pick,.bar.mini .pins{display:none}',
      '.logo{width:22px;height:22px;border-radius:50%;background:#7048e8;display:flex;align-items:center;justify-content:center;font-weight:700;font-size:12px}',
      '.proj{max-width:260px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;padding:0 4px}',
      '.bar button{border:0;border-radius:999px;padding:6px 12px;font-size:13px;cursor:pointer;background:rgba(255,255,255,.12);color:#fff}',
      '.bar button:hover{background:rgba(255,255,255,.22)}',
      '.bar .pick{background:#7048e8}.bar .pick.active{background:#f08c00}.bar .pins.on{background:rgba(255,255,255,.28)}',
      '.bar button:disabled{opacity:.5;cursor:default}',
      '.hl{position:fixed;border:2px solid #7048e8;background:rgba(112,72,232,.08);border-radius:2px;pointer-events:none}',
      '.hl.chosen{border-color:#f08c00;background:rgba(240,140,0,.08)}',
      '.layer{position:fixed;inset:0;pointer-events:none}',
      '.pin{position:fixed;transform:translate(-50%,-50%);width:24px;height:24px;border-radius:50% 50% 50% 0;border:2px solid #fff;color:#fff;font-size:11px;font-weight:700;display:flex;align-items:center;justify-content:center;box-shadow:0 2px 6px rgba(0,0,0,.3);pointer-events:auto;cursor:pointer;padding:0}',
      '.pin.draft{background:#f08c00;pointer-events:none}',
      '.panel{position:fixed;right:16px;bottom:64px;width:340px;max-height:calc(100vh - 96px);overflow:auto;background:#fff;color:#111;border-radius:12px;box-shadow:0 10px 30px rgba(0,0,0,.25);padding:14px;pointer-events:auto;font-size:13px}',
      '.head{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:4px}',
      '.head .x{border:0;background:none;font-size:18px;cursor:pointer;color:#666}',
      '.muted{color:#6b7280;font-size:12px}',
      'label{display:block;margin-top:10px;font-weight:600;font-size:12px;color:#333}',
      'label.row{display:flex;align-items:center;gap:6px;font-weight:400}',
      'input:not([type=checkbox]),textarea,select{display:block;width:100%;margin-top:4px;padding:7px 8px;border:1px solid #d0d5dd;border-radius:8px;font-size:13px;font-weight:400;color:#111;background:#fff}',
      '.paste{margin-top:8px;padding:8px;border:1px dashed #c7cdd6;border-radius:8px;text-align:center}',
      '.err{margin-top:8px;color:#c92a2a;font-size:12px}',
      '.actions{display:flex;justify-content:flex-end;gap:8px;margin-top:12px}',
      '.actions button{border:0;border-radius:8px;padding:8px 14px;font-size:13px;cursor:pointer}',
      '.cancel{background:#f1f3f5;color:#333}.submit{background:#7048e8;color:#fff}.submit:disabled{opacity:.6}',
      '.go{display:inline-block;margin-top:10px;color:#7048e8;font-weight:600;text-decoration:none}',
      '.toast{position:fixed;right:16px;bottom:64px;background:#2b8a3e;color:#fff;padding:8px 14px;border-radius:8px;font-size:13px;pointer-events:none}',
    ].join('')

    // 시작은 맨 끝에서 — 위의 변수(ui·CSS 등)가 다 정해진 뒤에 툴바를 그린다
    if (session) start()
    else if (window.opener) handshake()
    // 입장권도, 열어 준 QAting 탭도 없으면 일반 방문자 — 아무것도 하지 않는다
  }
})()
