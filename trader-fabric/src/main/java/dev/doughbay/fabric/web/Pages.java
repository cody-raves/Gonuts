package dev.doughbay.fabric.web;

/**
 * The two pages the local panel serves, drawn in the look of the screen the
 * mod has in game: the same dark panels, edges, gold titles and button
 * states, taken from UiTheme. Everything is in the page itself; no script,
 * font or picture comes from anywhere but this computer.
 *
 * <p>The lettering is Monocraft, a pixel face drawn after the game's own and
 * published under the SIL Open Font License, which the game's font is not. It
 * ships beside its licence in {@code doughbay/web}.
 */
final class Pages {
    private Pages() {
    }

    /**
     * UiTheme's colours, by its names. The game's screen is dark and nothing
     * else, so this page is too, whatever the browser prefers.
     */
    private static final String STYLE = """
            :root { --background:#0c121b; --surface:#182230; --inset:#101823; --edge:#2c3b4c; --hover:#243747;
              --selected:#354034; --text:#f0eee7; --secondary:#b6c2cd; --muted:#8799aa; --gold:#f4cd7a;
              --teal:#56dccb; --good:#77deb0; --warn:#f3b576; --bad:#ff858c;
              --deny:#e0553b; --pick:#2f4a6b; --pickedge:#5b9bff; --bar:#5fcf78; --barzero:#2a2d36;
              color-scheme: dark; }
            @font-face { font-family:"GoNuts Pixel"; src:url(/font.ttf) format("truetype"); font-display:swap; }
            * { box-sizing: border-box; }
            body { margin:0; background:var(--background); color:var(--text);
              font: 16px/1.45 "GoNuts Pixel", "Monocraft", ui-monospace, "Cascadia Mono", Consolas, "DejaVu Sans Mono", monospace; }
            main { max-width: 1180px; margin: 0 auto; padding: 12px 16px 32px; }
            .panel { background:var(--surface); border:1px solid var(--edge); border-radius:7px;
              box-shadow: 2px 3px 0 rgba(0,0,0,.31); }
            .label { color:var(--muted); font-size:12px; letter-spacing:.08em; text-transform:uppercase; }
            .title { color:var(--gold); font-size:13px; letter-spacing:.08em; text-transform:uppercase;
              padding:8px 10px 6px; border-bottom:1px solid var(--edge); margin:0; font-weight:400; }
            header { display:flex; align-items:center; gap:12px; padding:8px 12px; flex-wrap:wrap; }
            header img { height:44px; width:auto; image-rendering:pixelated; }
            #state { background:var(--inset); border:1px solid var(--edge); border-radius:4px; padding:3px 10px;
              color:var(--gold); letter-spacing:.08em; text-transform:uppercase; font-size:13px; }
            #state.good { color:var(--good); } #state.bad { color:var(--bad); }
            header .gap { flex:1 1 auto; }
            #clock { color:var(--secondary); font-variant-numeric: tabular-nums; }
            .hud { display:grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap:8px; margin-top:10px; }
            .tile { background:var(--inset); border:1px solid var(--edge); border-radius:4px; padding:7px 10px; min-width:0; }
            .tile b { display:block; font-weight:400; font-size:17px; color:var(--text); font-variant-numeric: tabular-nums;
              overflow-wrap:anywhere; }
            .now { margin-top:10px; padding:8px 10px 10px; }
            .now .label { color:var(--warn); }
            #now { color:var(--text); overflow-wrap:anywhere; }
            .work { display:grid; grid-template-columns: minmax(0, 1fr); gap:10px; margin-top:10px; }
            nav { display:flex; gap:6px; overflow-x:auto; padding:8px; -webkit-overflow-scrolling:touch; }
            @media (min-width: 860px) {
              .work { grid-template-columns: 190px minmax(0, 1fr); align-items:start; }
              nav { flex-direction:column; overflow:visible; }
            }
            button, select, input, textarea { font: inherit; color:var(--text); background:var(--surface);
              border:1px solid var(--edge); border-radius:4px; padding:9px 12px; min-height:42px; }
            [hidden] { display:none !important; }
            button { cursor:pointer; text-align:center; }
            button:hover:not(:disabled) { background:var(--hover); }
            button:disabled { background:var(--inset); color:var(--muted); cursor:default; }
            button:focus-visible, select:focus-visible, input:focus-visible, textarea:focus-visible,
            .wide:focus-visible {
              outline:none; border-color:var(--gold); box-shadow:0 0 0 1px var(--gold); }
            nav button { text-align:left; white-space:nowrap; flex:0 0 auto; border-left-width:3px; }
            nav button[aria-current="page"] { background:var(--selected); color:var(--gold); border-left-color:var(--gold); }
            button.go { color:var(--good); border-color:var(--good); }
            button.stop { color:var(--bad); border-color:var(--bad); }
            button.pick { color:var(--gold); }
            button.go:disabled, button.stop:disabled { border-color:var(--edge); color:var(--muted); }
            .body { padding:10px; }
            .wide { overflow-x:auto; -webkit-overflow-scrolling:touch; background:var(--inset);
              border:1px solid var(--edge); border-radius:4px; padding:6px; }
            #shot { display:block; width:100%; min-width:720px; height:auto; }
            #notice { min-height:22px; margin:8px 2px 2px; color:var(--secondary); overflow-wrap:anywhere; }
            .row { display:flex; flex-wrap:wrap; gap:6px; margin-top:8px; }
            .row button { flex:1 1 auto; }
            .row select { flex:1 1 100%; max-width:100%; }
            form.out button { min-height:34px; padding:5px 12px; }
            #own { min-width:0; }
            #evasion-history td:last-child { white-space:normal; min-width:18em; max-width:40em; overflow-wrap:anywhere; }
            .muted { color:var(--muted); }
            .sr { position:absolute; width:1px; height:1px; overflow:hidden; clip-path:inset(50%); white-space:nowrap; }
            .bar { display:flex; flex-wrap:wrap; gap:6px; align-items:center; margin-bottom:10px; }
            .bar input[type=search] { flex:1 1 200px; min-width:0; background:var(--inset); }
            .line { display:flex; flex-wrap:wrap; gap:6px; align-items:center; }
            .line input { width:120px; background:var(--inset); text-align:right; }
            .line .unit { color:var(--muted); }
            textarea { display:block; width:100%; min-height:88px; resize:vertical; background:var(--inset); }
            .cards { columns: 2 330px; column-gap:10px; }
            .card { break-inside:avoid; background:var(--inset); border:1px solid var(--edge); border-radius:4px;
              margin:0 0 10px; }
            .setline { display:flex; align-items:center; gap:8px; padding:0 10px; }
            .setline:hover { background:var(--hover); }
            .setname, .val { background:none; border:0; border-radius:0; padding:7px 0; min-height:36px; }
            .setname:hover:not(:disabled), .val:hover:not(:disabled) { background:none; }
            .setname { flex:1 1 auto; min-width:0; text-align:left; overflow-wrap:anywhere; }
            .val { flex:0 0 auto; color:var(--secondary); text-align:right; white-space:nowrap;
              font-variant-numeric:tabular-nums; }
            .val.long { flex:0 1 auto; max-width:55%; overflow:hidden; text-overflow:ellipsis; }
            .val.changed { color:var(--gold); }
            .switch { min-height:30px; min-width:60px; padding:2px 12px; flex:0 0 auto; }
            .switch.on { color:var(--good); border-color:var(--good); }
            .switch.off { color:var(--muted); }
            .tag { color:var(--teal); font-size:11px; border:1px solid var(--edge); border-radius:3px; padding:0 5px;
              white-space:nowrap; flex:0 0 auto; }
            .edit { padding:2px 10px 10px; background:var(--surface); border-top:1px solid var(--edge);
              border-bottom:1px solid var(--edge); }
            .desc { color:var(--secondary); font-size:13px; margin:8px 0; max-width:65ch; }
            .range { color:var(--muted); font-size:12px; margin:8px 0 0; }
            .seg { display:flex; flex-wrap:wrap; }
            .seg button { flex:1 1 auto; border-radius:0; margin-right:-1px; min-height:42px; padding:6px 7px;
              font-size:14px; }
            .seg button[aria-pressed="true"] { background:var(--selected); color:var(--gold); }
            .legend { color:var(--muted); font-size:12px; margin:0 0 8px; }
            .legend .d { color:var(--deny); } .legend .a { color:var(--good); } .legend .g { color:var(--gold); }
            .grid { display:grid; grid-template-columns:repeat(auto-fill, minmax(88px, 1fr)); gap:4px; }
            .it { position:relative; display:flex; flex-direction:column; align-items:center; gap:4px;
              padding:7px 3px 5px; min-height:80px; background:var(--inset); border-radius:3px; }
            .it img, .it .noicon { width:32px; height:32px; image-rendering:pixelated; flex:0 0 auto; }
            .it .noicon { display:grid; place-items:center; color:var(--muted); border:1px dashed var(--edge); }
            .it .nm { font-size:11px; line-height:1.25; color:var(--secondary); overflow-wrap:anywhere; }
            .it.deny { border-color:var(--deny); } .it.allow { border-color:var(--good); }
            .it.track { border-color:var(--gold); }
            .it .corner { position:absolute; top:2px; right:2px; width:9px; height:4px; background:var(--gold); }
            .it.picked, .it.picked:hover:not(:disabled) { background:var(--pick); border-color:var(--pickedge);
              box-shadow:0 0 0 1px var(--pickedge); }
            .acts { position:sticky; bottom:0; display:flex; flex-wrap:wrap; gap:4px; align-items:center;
              padding:8px 0 2px; margin-top:8px; background:var(--surface); border-top:1px solid var(--edge); }
            .acts button { flex:1 1 78px; padding:6px 4px; }
            .acts .count { flex:1 1 100%; color:var(--secondary); font-size:13px; }
            .more { display:block; width:100%; margin-top:8px; }
            .headline { display:flex; flex-wrap:wrap; align-items:center; gap:8px; margin-bottom:10px; }
            .headline .what { color:var(--gold); flex:1 1 240px; overflow-wrap:anywhere; }
            .headline .stamp { color:var(--muted); font-size:13px; }
            .stats { display:grid; grid-template-columns:repeat(auto-fit, minmax(150px, 1fr)); gap:6px;
              margin-bottom:10px; }
            .stat .label { display:block; font-size:11px; letter-spacing:.04em; }
            .stat { background:var(--inset); border:1px solid var(--edge); border-radius:4px; padding:7px 10px; }
            .stat b { display:block; font-weight:400; font-size:18px; font-variant-numeric:tabular-nums; }
            .stat small { display:block; color:var(--muted); font-size:11px; }
            .split { display:grid; grid-template-columns:minmax(0, 1fr); gap:10px; }
            @media (min-width: 1000px) { .split { grid-template-columns:minmax(0, 5fr) minmax(0, 2fr);
              align-items:start; } }
            .detail { display:grid; grid-template-columns:minmax(0, 1fr); gap:14px; padding:10px; }
            @media (min-width: 760px) { .detail { grid-template-columns:minmax(0, 400px) minmax(0, 1fr); } }
            table { border-collapse:collapse; width:100%; font-size:13px; font-variant-numeric:tabular-nums; }
            th { color:var(--muted); font-weight:400; text-align:left; text-transform:uppercase; font-size:11px;
              letter-spacing:.08em; padding:5px 8px; white-space:nowrap; }
            td { padding:3px 6px; white-space:nowrap; border-top:1px solid var(--edge); }
            th { padding:5px 6px; }
            tr.go { cursor:pointer; }
            tr.go:hover td { background:var(--hover); }
            td img { width:16px; height:16px; image-rendering:pixelated; vertical-align:middle; margin-right:6px; }
            button.link { background:none; border:0; border-radius:0; padding:3px 0; min-height:28px; text-align:left;
              text-decoration:underline; text-decoration-color:var(--muted); text-underline-offset:3px; }
            button.link:hover:not(:disabled) { background:none; color:var(--gold); }
            .spark { display:inline-flex; align-items:flex-end; gap:1px; height:18px; width:60px;
              vertical-align:middle; }
            .strip { display:flex; align-items:flex-end; gap:2px; height:84px; margin-top:6px; }
            .spark i, .strip i { flex:1 1 0; background:var(--bar); min-height:1px; }
            .spark i.z, .strip i.z { background:var(--barzero); }
            .spark i.n, .strip i.n { background:var(--gold); }
            .ticks { display:flex; color:var(--muted); font-size:11px; }
            .ticks span { flex:1 1 0; }
            .kv { margin:0; }
            .kv div { display:flex; flex-wrap:wrap; justify-content:space-between; gap:0 14px; padding:2px 0; }
            .kv dt { color:var(--secondary); }
            .kv dd { margin:0 0 0 auto; text-align:right; overflow-wrap:break-word; min-width:0; }
            .entry { padding:5px 10px; border-top:1px solid var(--edge); }
            .entry .top { display:flex; flex-wrap:wrap; gap:2px 10px; justify-content:space-between; }
            .entry small { display:block; color:var(--muted); font-size:11px; overflow-wrap:anywhere; }
            .lessons { padding:0 10px 10px; }
            .lessons p { margin:2px 0; font-size:13px; }
            .t-text { color:var(--text); } .t-secondary { color:var(--secondary); } .t-muted { color:var(--muted); }
            .t-good { color:var(--good); } .t-warn { color:var(--warn); } .t-bad { color:var(--bad); }
            .t-gold { color:var(--gold); }
            #toast { position:fixed; left:50%; bottom:14px; transform:translateX(-50%); z-index:5;
              width:max-content; max-width:min(92vw, 560px); background:var(--inset); border:1px solid var(--gold);
              border-radius:4px; padding:8px 14px; box-shadow:2px 3px 0 rgba(0,0,0,.4); overflow-wrap:anywhere; }
            #toast.bad { border-color:var(--bad); color:var(--bad); }
            .door { max-width:360px; margin:10vh auto 0; }
            .door .body { padding:14px; }
            .door img { display:block; width:210px; max-width:70%; height:auto; margin:4px auto 14px; image-rendering:pixelated; }
            .door input { width:100%; font-size:24px; letter-spacing:.25em; text-align:center; background:var(--inset); }
            .door button { width:100%; margin-top:10px; }
            .door p { color:var(--secondary); font-size:13px; }
            .door .bad { color:var(--bad); }
            """;

    private static final String HEAD = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
            + "<title>GoNuts panel</title><style>" + STYLE + "</style></head><body><main>";

    static String login(String message) {
        String note = message == null || message.isBlank() ? ""
                : "<p class=\"bad\" role=\"alert\">" + escape(message) + "</p>";
        return HEAD + "<div class=\"door panel\"><h1 class=\"title\">Web panel</h1><div class=\"body\">"
                + "<img src=\"/logo.png\" alt=\"GoNuts\">" + note
                + "<form method=\"post\" action=\"/login\">"
                + "<label class=\"label\" for=\"code\">PIN</label>"
                + "<input id=\"code\" name=\"code\" type=\"password\" inputmode=\"numeric\" autocomplete=\"off\" "
                + "maxlength=\"12\" autofocus required>"
                + "<button class=\"pick\" type=\"submit\">Open the panel</button></form>"
                + "<p>The PIN is the one set on the GoNuts screen in game, under Autopilot, Web panel.</p>"
                + "</div></div></main></body></html>";
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * The panel. The header and the tiles under it are the HUD's, less its
     * grid of slot squares: "72 / 90" in a tile says it, and ninety squares
     * across the top of a page said it again at ten times the size. The pages
     * run down the side as they do on the screen in game. Most are the picture
     * the mod draws, with its controls underneath. Settings, Items and Rivals
     * are pages of the panel's own: the same rows, grid and tables the screen
     * in game has, built here from what {@code /api} answers, so they can be
     * searched, ticked and changed and not only looked at.
     */
    static final String APP = HEAD
            + "<header class=\"panel\"><img src=\"/logo.png\" alt=\"GoNuts\">"
            + "<span id=\"state\">...</span><span class=\"gap\"></span><span id=\"clock\"></span>"
            + "<form class=\"out\" method=\"post\" action=\"/logout\"><button type=\"submit\">Lock</button></form></header>"
            + "<section class=\"hud\" aria-label=\"The trader now\">"
            + "<div class=\"tile\"><span class=\"label\">Auction slots</span><b id=\"slots\">-</b></div>"
            + "<div class=\"tile\"><span class=\"label\">Bank</span><b id=\"bank\">-</b></div>"
            + "<div class=\"tile\"><span class=\"label\">Trades</span><b id=\"trades\">-</b></div>"
            + "<div class=\"tile\"><span class=\"label\">Account</span><b id=\"who\">-</b></div></section>"
            + "<section class=\"panel now\"><span class=\"label\">Now</span><div id=\"now\" role=\"status\"></div>"
            + "</section>"
            + "<section class=\"panel\" id=\"reportalert\" role=\"status\" hidden></section>"
            + "<div class=\"work\"><nav class=\"panel\" id=\"pages\" aria-label=\"Pages\"></nav>"
            + "<section class=\"panel\"><h1 class=\"title\" id=\"page\">&nbsp;</h1><div class=\"body\">"
            + "<div id=\"pic\">"
            + "<div class=\"wide\" tabindex=\"0\" aria-label=\"The page on view; scroll sideways to see all of it\">"
            + "<img id=\"shot\" alt=\"The page of the panel on view\" src=\"/panel.png\"></div>"
            + "<div id=\"notice\" role=\"status\"></div>"
            + "<div id=\"controls\"></div></div>"
            + "<div id=\"own\" hidden></div></div></section></div>"
            + "<div id=\"toast\" role=\"status\" aria-live=\"polite\" hidden></div>"
            + "</main><script>" + """
            const $ = id => document.getElementById(id);
            const shot = $('shot');
            const controls = $('controls');
            const pages = $('pages');
            const own = $('own');
            let last = '';
            let lastPages = '';
            let setting = null;
            let view = '';
            let ticks = 0;
            function put(id, text) { $(id).textContent = text; }
            function el(tag, cls, text) {
              const e = document.createElement(tag);
              if (cls) e.className = cls;
              if (text !== undefined && text !== null) e.textContent = text;
              return e;
            }
            function button(cls, text, press) {
              const b = el('button', cls, text);
              b.type = 'button';
              if (press) b.addEventListener('click', press);
              return b;
            }
            function picture() { shot.src = '/panel.png?t=' + Date.now(); }
            let toastTimer = 0;
            function toast(text, bad) {
              const t = $('toast');
              t.textContent = text || '';
              t.className = bad ? 'bad' : '';
              t.hidden = !text;
              clearTimeout(toastTimer);
              if (text) toastTimer = setTimeout(() => { t.hidden = true; }, 5000);
            }
            const SILENT = 'The game is not answering. Is it still running?';
            async function api(name, query) {
              const r = await fetch('/api/' + name + (query ? '?' + new URLSearchParams(query) : ''), { cache: 'no-store' });
              if (r.status === 401) { location.reload(); throw new Error('locked'); }
              if (!r.ok) throw new Error('HTTP ' + r.status);
              return r.json();
            }
            async function change(name, fields) {
              try {
                const r = await fetch('/api/' + name, { method: 'POST', headers: { 'X-GoNuts': '1',
                  'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(fields) });
                if (r.status === 401) { location.reload(); return null; }
                if (!r.ok) { toast('The game did not take that (' + r.status + ').', true); return null; }
                const j = await r.json();
                toast(j.note, !j.ok);
                return j;
              } catch (e) {
                toast(SILENT, true);
                return null;
              }
            }
            function failed() { own.replaceChildren(el('p', 'muted', SILENT)); }
            async function act(id, value, text) {
              const body = new URLSearchParams();
              body.set('id', id);
              if (value !== undefined && value !== null) body.set('value', value);
              if (text !== undefined && text !== null) body.set('text', text);
              await fetch('/act', { method: 'POST', headers: { 'X-GoNuts': '1',
                'Content-Type': 'application/x-www-form-urlencoded' }, body });
              setTimeout(refresh, 350);
              setTimeout(refresh, 1500);
            }
            function press(id) {
              if (id === 'ctl:search') {
                const item = prompt('Item to search for, for example golden apple');
                if (item) act(id, null, item);
                return;
              }
              if (id === 'tune:set') {
                const text = prompt(setting ? setting.label + '\\n' + setting.description : 'New value',
                  setting ? setting.value : '');
                if (text !== null && text !== '') act(id, null, text);
                return;
              }
              act(id, '');
            }
            function hud(h) {
              if (!h) return;
              const state = $('state');
              state.textContent = h.state || '...';
              const s = (h.state || '').toUpperCase();
              state.className = s === 'STOPPED' ? 'bad' : s === 'PAUSED' ? '' : 'good';
              put('slots', h.listed + ' / ' + h.slots);
              put('bank', h.bank || '-');
              put('trades', String(h.trades));
              put('who', h.who || '-');
              put('now', h.now || '');
              put('clock', h.clock || '');
            }

            // ---- the pages down the side: the pictures the mod draws, and the panel's own
            const OWN = {
              settings: { label: 'Settings', open: openSettings },
              analyzer: { label: 'Analyzer', open: openAnalyzer },
              evasion: { label: 'Evasion', open: openEvasion },
              items: { label: 'Items', open: openItems },
              prices: { label: 'Flips', open: openPrices },
              rivals: { label: 'Rivals', open: openRivals },
              staff: { label: 'Staff Watch', open: openStaff },
              reports: { label: 'Report bug', open: openReports },
              proxies: { label: 'Proxies', open: openProxies }
            };
            function show(name) {
              view = OWN[name] ? name : '';
              try { history.replaceState(null, '', view ? '#' + view : location.pathname); } catch (e) { }
              $('pic').hidden = !!view;
              own.hidden = !view;
              lastPages = '';
              last = '';
              if (view) {
                put('page', OWN[view].label);
                own.replaceChildren(el('p', 'muted', 'Reading...'));
                OWN[view].open();
              }
              refresh();
            }
            function tabs(pick) {
              const options = (pick ? pick.options : []) || [];
              const shape = JSON.stringify(options) + view;
              if (shape === lastPages) return;
              lastPages = shape;
              pages.replaceChildren();
              const add = (label, current, go) => {
                const b = button('', label, go);
                if (current) b.setAttribute('aria-current', 'page');
                pages.appendChild(b);
              };
              const mine = name => add(OWN[name].label, view === name, () => show(name));
              mine('reports'); mine('proxies');
              for (const o of options) {
                // Three of the mod's pictures have a page of their own here, which does what the picture only showed.
                if (o.value === 'RIVALS') { mine('rivals'); continue; }
                if (o.value === 'STAFF') { mine('staff'); continue; }
                if (o.value === 'EVASION') { mine('evasion'); continue; }
                // The Flips picture is the Your prices page here, which edits the whole list.
                if (o.value === 'FLIPS') continue;
                if (o.value === 'TUNE') { mine('items'); mine('prices'); mine('settings'); continue; }
                add(o.value === 'ITEMS' ? 'Item profit' : o.label, !view && o.default, () => {
                  if (view) show('');
                  act('page:pick', o.value);
                });
              }
            }
            function draw(state) {
              hud(state.hud);
              setting = state.setting || null;
              let pick = null;
              const rows = [];
              for (const row of state.components || []) {
                const rest = [];
                for (const c of row.components || []) {
                  if (c.type === 3 && c.custom_id === 'page:pick') pick = c; else rest.push(c);
                }
                if (rest.length) rows.push(rest);
              }
              tabs(pick);
              if (view) return;
              put('page', state.page || '');
              put('notice', state.notice || '');
              const shape = JSON.stringify(rows);
              if (shape === last || controls.contains(document.activeElement) && document.activeElement.tagName === 'SELECT') return;
              last = shape;
              controls.replaceChildren();
              for (const row of rows) {
                const div = el('div', 'row');
                for (const c of row) {
                  if (c.type === 2) {
                    const b = button(c.style === 3 ? 'go' : c.style === 4 ? 'stop' : c.style === 1 ? 'pick' : '', c.label,
                      () => press(c.custom_id));
                    b.disabled = !!c.disabled;
                    div.appendChild(b);
                  } else if (c.type === 3) {
                    const s = el('select');
                    s.setAttribute('aria-label', c.placeholder || 'Choose');
                    const first = el('option', '', c.placeholder || 'Choose');
                    first.value = '';
                    first.disabled = true;
                    first.selected = true;
                    s.appendChild(first);
                    for (const o of c.options || []) {
                      const opt = el('option', '', o.label);
                      opt.value = o.value;
                      s.appendChild(opt);
                    }
                    s.addEventListener('change', () => { const v = s.value; s.blur(); act(c.custom_id, v); });
                    div.appendChild(s);
                  }
                }
                controls.appendChild(div);
              }
            }

            // ---- Settings: every setting the screen in game has, changed where it stands
            const S = { data: null, q: '', open: '' };
            let reportData = {}, reportTimer;
            async function reportNotice() {
              try {
                reportData = await api('reports');
                const box = $('reportalert');
                box.hidden = !reportData.alert;
                box.replaceChildren();
                if (reportData.alert) box.append(el('p', '', reportData.notice),
                  button('', 'Review report', () => show('reports')),
                  button('', 'Dismiss', async () => { await change('reports', {action:'dismiss', incident:reportData.incident}); await reportNotice(); }));
              } catch (e) { /* Panel status must continue when report status is unavailable. */ }
            }
            async function openReports() {
              clearTimeout(reportTimer);
              if (view !== 'reports') return;
              await reportNotice();
              if (view !== 'reports') return;
              const description = el('textarea'), expected = el('textarea');
              description.maxLength = expected.maxLength = 1500;
              description.rows = 5; expected.rows = 3;
              description.style.width = expected.style.width = '100%';
              description.style.boxSizing = expected.style.boxSizing = 'border-box';
              description.value = reportData.description || '';
              expected.value = reportData.expected || '';
              const logLabel = el('p','muted','Sanitized diagnostic logs are included automatically.');
              const privacy = el('p', 'muted', 'Reports are public on GitHub. Diagnostics remove player names, chat and personal details. Review the preview for anything sensitive you typed. Nothing is uploaded until you send. This reports the client hosting this panel.');
              const status = el('p', '', ''), preview = el('pre', '', '');
              preview.style.whiteSpace = 'pre-wrap'; preview.style.overflowWrap = 'anywhere';
              let preparedId = '', blockedId = '', changedSinceSend = true;
              const fields = action => ({action, description:description.value, expected:expected.value, consent:'true'});
              const send = button('', 'Send report', async () => {
                send.disabled = true; changedSinceSend = false;
                await change('reports', fields('submit'));
                poll();
              });
              const prepare = button('', 'Preview (optional)', async () => {
                preparedId = ''; blockedId = ''; send.disabled = true; prepare.disabled = true;
                const result = await change('reports', fields('prepare'));
                if (!result || !result.ok) prepare.disabled = false;
                poll();
              });
              const link = el('a', '', 'Open submitted issue'); link.hidden = true; link.rel = 'noopener noreferrer'; link.target = '_blank';
              function changed() { blockedId = reportData.id || ''; preparedId = ''; changedSinceSend = true; send.disabled = false; preview.textContent = ''; }
              description.oninput = expected.oninput = log.onchange = changed;
              async function poll() {
                clearTimeout(reportTimer);
                if (view !== 'reports' || !status.isConnected) return;
                try {
                  const d = await api('reports'); reportData = d;
                  status.textContent = d.note || '';
                  prepare.disabled = !!d.busy;
                  if (d.id && d.id !== preparedId && d.id !== blockedId && d.preview && !d.busy) {
                    preparedId = d.id; preview.textContent = d.preview;
                  }
                  send.disabled = !!d.busy || !changedSinceSend && !!d.issueUrl || Date.now() < (d.retryAt || 0);
                  link.hidden = !/^https:\\/\\/github\\.com\\/cody-raves\\/Gonuts\\/issues\\/[1-9][0-9]*$/.test(d.issueUrl || '');
                  if (!link.hidden) link.href = d.issueUrl;
                } catch (e) { status.textContent = 'Report status unavailable. Try again shortly.'; send.disabled = true; }
                reportTimer = setTimeout(poll, 1500);
              }
              own.replaceChildren(el('h2', '', 'Report bug'), privacy, el('label', '', 'What happened?'), description,
                el('label', '', 'What did you expect?'), expected, logLabel, el('p', 'muted', 'Send report sanitizes and attaches diagnostics automatically, then publishes them to GitHub.'), send, prepare, status, preview, link);
              poll();
            }

            async function openProxies() {
              try {
                const data=await api('proxies'); if(view!=='proxies')return;
                const active=data.profiles.find(p=>p.id===data.selected);
                const state=el('p','',data.blocked?'Connections blocked: settings unavailable':data.enabled?'Active proxy: '+active.name:'Direct connection: proxy disabled');
                const pick=el('select'), fresh=el('option','','New profile');fresh.value='';pick.appendChild(fresh);
                for(const p of data.profiles){const o=el('option','',p.name+(p.id===data.selected&&data.enabled?' (active)':''));o.value=p.id;pick.appendChild(o);}
                pick.value=active?active.id:'';
                const fields={},form=el('div');
                for(const [key,label,type] of [['name','Profile name','text'],['host','Proxy host','text'],['port','Port','number'],['username','Username (blank keeps saved)','text'],['password','Password (blank keeps saved)','password']]){
                  const input=el('input');input.type=type;input.autocomplete=key==='password'?'new-password':'off';input.style.width='100%';input.style.boxSizing='border-box';
                  input.maxLength=key==='name'?48:key==='host'?253:255;
                  const wrap=el('label','',label);wrap.appendChild(input);form.append(wrap,el('br'));fields[key]=input;
                }
                fields.port.min=1;fields.port.max=65535;
                const kind=el('select');for(const type of ['SOCKS5','SOCKS4']){const o=el('option','',type);o.value=type;kind.appendChild(o);}
                const clear=el('input');clear.type='checkbox';const clearLabel=el('label','',' Clear saved credentials');clearLabel.prepend(clear);
                function fill(){const p=data.profiles.find(p=>p.id===pick.value);fields.name.value=p?p.name:'';fields.host.value=p?p.host:'';fields.port.value=p?p.port:1080;fields.username.value=fields.password.value='';kind.value=p?p.type:'SOCKS5';clear.checked=false;}
                pick.onchange=fill;fill();const actions=el('div');let saving=false;
                async function action(what){
                  if(saving)return;saving=true;for(const b of actions.querySelectorAll('button'))b.disabled=true;
                  const values={action:what,id:pick.value,type:kind.value,clearCredentials:String(clear.checked)};
                  for(const [key,input] of Object.entries(fields))values[key]=input.value;
                  const result=await change('proxies',values);fields.username.value=fields.password.value='';
                  if(result&&result.ok)await openProxies();else{saving=false;for(const b of actions.querySelectorAll('button'))b.disabled=false;}
                }
                actions.append(button('','Save profile',()=>action('save')),button('','Use proxy',()=>action('enable')),button('','Disable proxy',()=>action('disable')),button('','Delete profile',()=>action('delete')));
                own.replaceChildren(el('h2','','Proxies'),state,el('p','muted',data.note),pick,form,el('label','','Protocol'),kind,clearLabel,actions,
                  el('p','muted','SOCKS5 supports username/password. SOCKS4 supports username only. Saved credentials are never returned to the browser. Save edits before choosing Use proxy.'));
              }catch(e){failed();}
            }

            async function openEvasion() {
              try {
                const data=await api('evasion');
                if(view!=='evasion')return;
                const actions=el('div','line');
                const guardToggle=button('',data.enabled?'Guard: ON':'Guard: OFF',async()=>{
                  if((await change('evasion',{action:'guard'}))?.ok)openEvasion();
                });
                guardToggle.id='evasion-guard-toggle';actions.append(guardToggle);
                const route=el('select');
                for(const [value,label] of [['rtp','Random teleport (/rtp)'],['home','Cycle named homes (/home)']]){
                  const option=el('option','',label);option.value=value;route.appendChild(option);
                }
                route.value=data.useHomes?'home':'rtp';route.setAttribute('aria-label','Teleport destination');
                route.onchange=async()=>{if((await change('evasion',{action:'route',route:route.value}))?.ok)openEvasion();};
                actions.appendChild(route);
                const homes=el('input');homes.value=(data.homes||[]).join(', ');homes.maxLength=2048;
                homes.placeholder='base, vault, farm';homes.setAttribute('aria-label','Home names in rotation order');
                const save=button('pick','Save home names',async()=>{
                  if((await change('evasion',{action:'homes',names:homes.value}))?.ok)openEvasion();
                });
                const cards=el('div','cards');
                for(const group of data.groups||[]){
                  const card=el('section','card');card.appendChild(el('h2','title',group.name));
                  for(const row of group.settings||[]){
                    if(row.key==='evasion.enabled'||row.key==='evasion.use_homes')continue;
                    card.appendChild(settingRow(row));
                  }cards.appendChild(card);
                }
                const activity=el('section','card');activity.id='evasion-history';
                own.replaceChildren(el('h2','','Evasion'),activity,actions,el('label','','Named homes'),homes,save,
                  el('p','muted','Set these homes on the server first. Enter names only, separated by commas. /home cycles in entered order; an empty list falls back to /rtp. Hive clients sharing this list need the same home names.'),cards);
                drawEvasionHistory(data);
              }catch(e){failed();}
            }
            function drawEvasionHistory(data) {
              if(view!=='evasion')return;
              const card=$('evasion-history');if(!card)return;
              const toggle=$('evasion-guard-toggle');if(toggle)toggle.textContent=data.enabled?'Guard: ON':'Guard: OFF';
              const summary=el('div','line');
              for(const text of ['Guard: '+data.guardStatus,'Pause ring: '+data.pauseRing+' blocks',
                'Escape ring: '+data.escapeRing+' blocks','On contact: '+data.onContact])summary.appendChild(el('span','',text));
              const history=data.history||{};
              const counts=el('p','muted',history.available?'Triggered 24h: '+history.triggered24h+
                (history.mostSeen7d?' · Most seen 7d: '+history.mostSeen7d.who+' ×'+history.mostSeen7d.count:''):
                'History is temporarily unavailable. Settings remain available.');
              const table=el('table'),head=el('thead'),header=el('tr'),body=el('tbody');
              for(const text of ['Time','Client','Who','Blocks','What happened'])header.appendChild(el('th','',text));
              head.appendChild(header);table.append(head,body);
              for(const event of history.events||[]) {
                const row=el('tr');
                for(const text of [new Date(event.at).toLocaleString(),event.client||'',event.who||'',
                  event.distance>0?String(Math.round(event.distance)):'',event.action||''])row.appendChild(el('td','',text));
                body.appendChild(row);
              }
              const wide=el('div','wide');wide.appendChild(table);
              const note=el('p','muted',history.available&&!history.events?.length?'No evasion events recorded.':
                'Latest 100 hive events. Times use your local clock. Guard controls apply to this client.');
              card.replaceChildren(el('h2','','Evasion activity'),summary,counts,wide,note,
                button('','Refresh history',refreshEvasionHistory));
            }
            let evasionHistoryLoading=false;
            async function refreshEvasionHistory() {
              if(view!=='evasion'||evasionHistoryLoading)return;
              evasionHistoryLoading=true;
              try{const data=await api('evasion');if(view==='evasion')drawEvasionHistory(data);}
              catch(e){if(view==='evasion')toast('Could not refresh evasion history. Your settings have been kept.',true);}
              finally{evasionHistoryLoading=false;}
            }
            async function openSavedPresets() {
              try {
                const d=await api('savedpresets'); if(!d.ok){toast(d.note,true);return;}
                const box=el('section','card');box.appendChild(el('h2','title','Saved presets'));
                box.appendChild(el('p','desc','Save a baseline, experiment, then apply a saved copy. Account slot counts and follow settings stay as they are.'));
                const name=el('input');name.maxLength=48;name.placeholder='New preset name';name.setAttribute('aria-label','New preset name');box.appendChild(name);
                box.appendChild(button('pick','Save current as new',async()=>{const r=await change('savedpresets',{action:'save',name:name.value});if(r&&r.ok)openSavedPresets();}));
                const search=el('input');search.placeholder='Search saved presets';search.setAttribute('aria-label','Search saved presets');box.appendChild(search);
                const rows=el('div');box.appendChild(rows);
                for(const p of d.presets||[]){
                  const row=el('section','card');row.dataset.name=p.name.toLowerCase();row.appendChild(el('h3','title',p.name));
                  row.appendChild(el('p','muted','Saved '+new Date(p.savedAt).toLocaleString()));
                  row.appendChild(button('pick','Apply',async()=>{if(confirm('Apply '+p.name+'? Save your current settings first if you want to switch back. Shared settings affect the hive.')){const r=await change('savedpresets',{action:'apply',id:p.id});if(r&&r.ok)openSettings();}}));
                  row.appendChild(button('','Copy code',()=>codeToClipboard(p.code)));
                  row.appendChild(button('','Rename',async()=>{const n=prompt('Preset name',p.name);if(n!==null){const r=await change('savedpresets',{action:'rename',id:p.id,name:n});if(r&&r.ok)openSavedPresets();}}));
                  row.appendChild(button('','Replace with current',async()=>{if(confirm('Overwrite '+p.name+' with your current settings?')){const r=await change('savedpresets',{action:'replace',id:p.id,name:p.name});if(r&&r.ok)openSavedPresets();}}));
                  row.appendChild(button('','Delete',async()=>{if(confirm('Delete '+p.name+'? Current settings stay unchanged.')){const r=await change('savedpresets',{action:'delete',id:p.id});if(r&&r.ok)openSavedPresets();}}));
                  rows.appendChild(row);
                }
                search.addEventListener('input',()=>{for(const row of rows.children)row.hidden=!row.dataset.name.includes(search.value.toLowerCase());});
                box.appendChild(button('','Back to settings',openSettings));own.replaceChildren(box);
              }catch(e){failed();}
            }
            async function codeToClipboard(code){
              try{await navigator.clipboard.writeText(code);toast('Code copied');}
              catch(e){const area=el('textarea');area.value=code;area.readOnly=true;own.appendChild(area);area.select();toast('Select and copy the code.');}
            }
            async function openAnalyzer(){
              try{
                const d=await api('analyzer'),box=el('section','card');box.appendChild(el('h2','title','Local settings analyzer'));
                box.appendChild(el('p','desc',d.note||''));
                box.appendChild(button('pick','Analyze local data',async()=>{const r=await change('analyzer',{});if(r&&r.ok){openAnalyzer();setTimeout(()=>{if(view==='analyzer')openAnalyzer();},1500);}}));
                box.appendChild(button('','Refresh report',openAnalyzer));
                const report=d.report;
                if(report){
                  for(const line of report.baseline||[])box.appendChild(el('p','desc',line));
                  for(const f of report.findings||[]){const card=el('section','card');card.appendChild(el('h3','title',f.title));card.appendChild(el('p','desc','Evidence: '+f.evidence));card.appendChild(el('p','desc','Suggested experiment: '+f.suggestion));card.appendChild(el('p','muted','Estimate: '+f.estimate));box.appendChild(card);}
                  if(Object.keys(report.numbers||{}).length||Object.keys(report.lists||{}).length)box.appendChild(button('pick','Save suggested preset',async()=>{const n=prompt('Name for this suggested preset');if(n!==null)await change('savedpresets',{action:'save',source:'proposal',name:n});}));
                  box.appendChild(button('','Copy report',()=>codeToClipboard(JSON.stringify(report,null,2))));
                }
                if(view==='analyzer')own.replaceChildren(box);
              }catch(e){failed();}
            }
            async function openSettings() {
              try {
                S.data = await api('settings');
                if (view === 'settings') drawSettings();
              } catch (e) { failed(); }
            }
            function sift() {
              const q = S.q.trim().toLowerCase();
              let any = false;
              for (const card of own.querySelectorAll('.card')) {
                let shown = 0;
                for (const row of card.querySelectorAll('.set')) {
                  const hit = !q || row.dataset.hay.includes(q);
                  row.hidden = !hit;
                  if (hit) shown++;
                }
                card.hidden = shown === 0;
                any = any || shown > 0;
              }
              const none = $('nomatch');
              if (none) { none.hidden = any; none.textContent = 'No settings match "' + S.q.trim() + '"'; }
            }
            function unfold(row, wrap) {
              const was = S.open;
              for (const e of own.querySelectorAll('.edit')) e.remove();
              for (const e of own.querySelectorAll('[aria-expanded="true"]')) e.setAttribute('aria-expanded', 'false');
              if (was === row.key) { S.open = ''; return; }
              S.open = row.key;
              wrap.appendChild(editor(row));
              wrap.querySelector('.setname').setAttribute('aria-expanded', 'true');
              const field = wrap.querySelector('.edit input, .edit textarea');
              if (field) field.focus();
            }
            function editor(row) {
              const box = el('div', 'edit');
              box.appendChild(el('p', 'desc', row.description));
              const saved = j => { if (j) { if(view==='evasion')openEvasion();else openSettings(); } };
              if (row.locked) {
                box.appendChild(el('p', 'range', 'This one is set on the GoNuts screen in game, not from a browser.'));
                return box;
              }
              if (row.list) {
                const area = el('textarea');
                area.value = row.value || '';
                area.setAttribute('aria-label', row.label);
                box.appendChild(area);
                const line = el('div', 'line');
                line.style.marginTop = '6px';
                line.appendChild(button('pick', 'Save', async () => saved(await change('list', { key: row.key, text: area.value }))));
                box.appendChild(line);
                if (row.local) box.appendChild(el('p', 'range', 'Kept for this PC only, not shared with a second account.'));
                return box;
              }
              if (!row.toggle) {
                const line = el('div', 'line');
                const input = el('input');
                input.type = 'text';
                input.inputMode = 'decimal';
                input.value = String(row.value);
                input.setAttribute('aria-label', row.label + (row.unit ? ', in ' + row.unit : ''));
                const bump = by => {
                  const n = parseFloat(input.value);
                  const v = Math.min(row.max, Math.max(row.min, (isNaN(n) ? row.value : n) + by));
                  input.value = String(Math.round(v * 100) / 100);
                };
                const go = async () => saved(await change('setting', { key: row.key, value: input.value }));
                input.addEventListener('keydown', e => { if (e.key === 'Enter') go(); });
                const minus = button('', '-', () => bump(-row.step));
                minus.setAttribute('aria-label', 'Less');
                const plus = button('', '+', () => bump(row.step));
                plus.setAttribute('aria-label', 'More');
                line.append(minus, input);
                if (row.unit) line.appendChild(el('span', 'unit', row.unit));
                line.append(plus, button('pick', 'Apply', go));
                box.appendChild(line);
              }
              const foot = el('div', 'line');
              foot.style.marginTop = '6px';
              const reset = button('', 'Reset to ' + row.standard,
                async () => saved(await change('setting', { key: row.key, reset: '1' })));
              reset.disabled = !row.changed;
              foot.appendChild(reset);
              box.appendChild(foot);
              const notes = [];
              if (!row.toggle) notes.push('From ' + row.least + ' to ' + row.most + '.');
              if (row.local) notes.push('Kept for this PC only, not shared with a second account.');
              if (notes.length) box.appendChild(el('p', 'range', notes.join(' ')));
              return box;
            }
            function settingRow(row) {
              const wrap = el('div', 'set');
              wrap.dataset.hay = (row.label + ' ' + row.key + ' ' + row.description).toLowerCase();
              const line = el('div', 'setline');
              const name = button('setname', row.label, () => unfold(row, wrap));
              name.setAttribute('aria-expanded', S.open === row.key ? 'true' : 'false');
              line.appendChild(name);
              if (row.local) line.appendChild(el('span', 'tag', 'this PC'));
              if (row.toggle) {
                const on = row.value >= 0.5;
                const sw = button('switch ' + (on ? 'on' : 'off'), row.shown, async () => {
                  if (await change('setting', { key: row.key, toggle: '1' })) { if(view==='evasion')openEvasion();else openSettings(); }
                });
                sw.setAttribute('role', 'switch');
                sw.setAttribute('aria-checked', on ? 'true' : 'false');
                sw.setAttribute('aria-label', row.label);
                line.appendChild(sw);
              } else {
                const v = button('val' + (row.changed ? ' changed' : '') + (row.list ? ' long' : ''), row.shown,
                  () => unfold(row, wrap));
                v.setAttribute('aria-label', row.label + ': ' + row.shown + '. Change it');
                line.appendChild(v);
              }
              wrap.appendChild(line);
              if (S.open === row.key) wrap.appendChild(editor(row));
              return wrap;
            }
            function drawSettings() {
              const d = S.data;
              if (!d) return;
              const bar = el('div', 'bar');
              const q = el('input');
              q.type = 'search';
              q.placeholder = 'Search settings...';
              q.value = S.q;
              q.setAttribute('aria-label', 'Search settings');
              q.addEventListener('input', () => { S.q = q.value; sift(); });
              bar.appendChild(q);
              const preset = el('select');
              preset.setAttribute('aria-label', 'Apply a preset');
              const first = el('option', '', 'Apply a preset');
              first.value = '';
              first.disabled = true;
              first.selected = true;
              preset.appendChild(first);
              for (const p of d.presets || []) preset.appendChild(el('option', '', p));
              preset.addEventListener('change', async () => {
                const name = preset.value;
                preset.selectedIndex = 0;
                if (!confirm('Apply the ' + name + ' preset? It changes several settings at once.')) return;
                if (await change('preset', { name })) openSettings();
              });
              bar.appendChild(preset);
              const codeBox = el('div', 'card');
              codeBox.hidden = true;
              const codeShow = (text, pasting) => {
                codeBox.hidden = false;
                codeBox.replaceChildren(el('h2', 'title', pasting ? 'Paste a settings code' : 'Your settings code'));
                const body = el('div', 'edit');
                const area = el('textarea');
                area.value = text;
                area.readOnly = !pasting;
                area.setAttribute('aria-label', 'Settings code');
                body.appendChild(el('p', 'desc', pasting
                  ? 'Paste a code somebody shared. It sets the trading settings it carries; your slot counts and follow settings stay as they are.'
                  : 'Everything set here in one line of text, to send to a friend. Slot counts, follow settings and the webhook are left out.'));
                body.appendChild(area);
                const line = el('div', 'line');
                line.style.marginTop = '6px';
                if (pasting) {
                  line.appendChild(button('pick', 'Apply the code', async () => {
                    const j = await change('code', { code: area.value });
                    if (j && j.ok) { codeBox.hidden = true; openSettings(); }
                  }));
                } else {
                  line.appendChild(button('pick', 'Copy', async () => {
                    area.select();
                    let done = false;
                    try { await navigator.clipboard.writeText(area.value); done = true; } catch (e) { }
                    if (!done) { try { done = document.execCommand('copy'); } catch (e) { } }
                    toast(done ? 'Copied' : 'Select the text and copy it by hand.', !done);
                  }));
                }
                line.appendChild(button('', 'Close', () => { codeBox.hidden = true; }));
                body.appendChild(line);
                codeBox.appendChild(body);
                area.focus();
                if (!pasting) area.select();
              };
              bar.appendChild(button('', 'Copy settings code', async () => {
                try { codeShow((await api('code')).code || '', false); } catch (e) { toast(SILENT, true); }
              }));
              bar.appendChild(button('', 'Paste a code', () => codeShow('', true)));
              bar.appendChild(button('', 'Saved presets', openSavedPresets));
              const cards = el('div', 'cards');
              for (const g of d.groups || []) {
                const card = el('section', 'card');
                card.appendChild(el('h2', 'title', g.name));
                if(g.name==='Evasion')continue;
                for (const row of g.settings || []) card.appendChild(settingRow(row));
                cards.appendChild(card);
              }
              const lists = el('section', 'card');
              lists.appendChild(el('h2', 'title', 'Items'));
              for (const l of d.lists || []) lists.appendChild(settingRow(Object.assign({ list: true, changed: !!l.value }, l)));
              cards.appendChild(lists);
              const none = el('p', 'muted');
              none.id = 'nomatch';
              none.hidden = true;
              own.replaceChildren(bar, codeBox, none, cards);
              sift();
            }

            // ---- Items: the grid the screen in game has, ticked and sorted onto its lists
            const I = { all: null, allow: new Set(), deny: new Set(), tracked: new Set(), q: '', show: 'all', cat: 'All',
              picked: new Set(), limit: 150 };
            function bare(id) { const c = id.indexOf(':'); return c >= 0 ? id.slice(c + 1) : id; }
            // The trader's own rule: the name itself, or the end of it (boots covers netherite_boots).
            function covers(set, b) {
              if (set.has(b) || set.has('=' + b)) return true;
              for (const t of set) if (!t.startsWith('=') && b.endsWith('_' + t)) return true;
              return false;
            }
            function takeLists(j) {
              I.allow = new Set(j.allow || []);
              I.deny = new Set(j.deny || []);
              I.tracked = new Set(j.tracked || []);
            }
            async function openItems() {
              try {
                const j = await api('items');
                I.all = (j.items || []).map(r => ({ id: r[0], bare: bare(r[0]), name: r[1], cat: r[2] }));
                takeLists(j);
                if (view === 'items') drawItems();
              } catch (e) { failed(); }
            }
            function matching() {
              const needle = I.q.trim().toLowerCase().split(' ').filter(Boolean).join('_');
              return (I.all || []).filter(e => (!needle || e.bare.includes(needle))
                && (I.cat === 'All' || e.cat === I.cat)
                && (I.show !== 'deny' || covers(I.deny, e.bare))
                && (I.show !== 'allow' || covers(I.allow, e.bare))
                && (I.show !== 'tracked' || I.tracked.has(e.bare)));
            }
            function tile(e) {
              const d = covers(I.deny, e.bare);
              const a = !d && covers(I.allow, e.bare);
              const t = I.tracked.has(e.bare);
              const b = button('it' + (d ? ' deny' : a ? ' allow' : t ? ' track' : '') + (I.picked.has(e.bare) ? ' picked' : ''));
              b.setAttribute('aria-pressed', I.picked.has(e.bare) ? 'true' : 'false');
              const img = el('img');
              img.loading = 'lazy';
              img.alt = '';
              img.width = 32;
              img.height = 32;
              img.addEventListener('error', () => img.replaceWith(el('span', 'noicon', e.name.charAt(0))));
              img.src = '/item/' + e.id + '.png';
              b.append(img, el('span', 'nm', e.name));
              const state = (d ? 'excluded' : a ? 'included' : '') + (t ? (d || a ? ', ' : '') + 'tracked' : '');
              if (state) b.appendChild(el('span', 'sr', state));
              if (t) b.appendChild(el('i', 'corner'));
              b.title = e.name + (state ? ' · ' + state : '');
              b.addEventListener('click', () => {
                const on = !I.picked.has(e.bare);
                if (on) I.picked.add(e.bare); else I.picked.delete(e.bare);
                b.classList.toggle('picked', on);
                b.setAttribute('aria-pressed', on ? 'true' : 'false');
                counted();
              });
              return b;
            }
            function counted() {
              const c = $('picked');
              if (c) c.textContent = I.picked.size + ' ticked';
            }
            function drawGrid() {
              const grid = $('grid');
              if (!grid) return;
              const found = matching();
              grid.replaceChildren();
              for (const e of found.slice(0, I.limit)) grid.appendChild(tile(e));
              const more = $('more');
              more.hidden = found.length <= I.limit;
              more.textContent = 'Show ' + Math.min(150, found.length - I.limit) + ' more of ' + found.length;
              const none = $('noitems');
              none.hidden = found.length > 0;
              none.textContent = I.all && I.all.length ? 'No items match.'
                : 'The game has not listed its items yet. Open this page again in a moment.';
              put('itemsums', 'Only these may be traded: ' + (I.allow.size || 'everything') + '  ·  never traded: '
                + I.deny.size + '  ·  tracked: ' + I.tracked.size);
              counted();
            }
            async function itemsDo(what) {
              if (!I.picked.size) { toast('Tick some items first.', true); return; }
              const j = await change('items', { ids: [...I.picked].join(','), do: what });
              if (j && j.ok) { takeLists(j); drawGrid(); }
            }
            function drawItems() {
              const bar = el('div', 'bar');
              const q = el('input');
              q.type = 'search';
              q.placeholder = 'Search items...';
              q.value = I.q;
              q.setAttribute('aria-label', 'Search items');
              q.addEventListener('input', () => { I.q = q.value; I.limit = 150; drawGrid(); });
              bar.appendChild(q);
              const seg = el('div', 'seg');
              seg.setAttribute('role', 'group');
              seg.setAttribute('aria-label', 'Which items to show');
              for (const [key, label] of [['all', 'All items'], ['allow', 'Included'], ['deny', 'Excluded'], ['tracked', 'Tracked']]) {
                const b = button('', label, () => { I.show = key; I.limit = 150; drawItems(); });
                b.setAttribute('aria-pressed', I.show === key ? 'true' : 'false');
                seg.appendChild(b);
              }
              bar.appendChild(seg);
              const cat = el('select');
              cat.setAttribute('aria-label', 'Group');
              const cats = ['All'];
              for (const e of I.all || []) if (!cats.includes(e.cat)) cats.push(e.cat);
              for (const c of cats) {
                const o = el('option', '', c === 'All' ? 'Every group' : c);
                o.value = c;
                o.selected = c === I.cat;
                cat.appendChild(o);
              }
              cat.addEventListener('change', () => { I.cat = cat.value; I.limit = 150; drawGrid(); });
              bar.appendChild(cat);
              const legend = el('p', 'legend');
              legend.append(el('span', 'd', 'Red edge'), ' never traded  ·  ', el('span', 'a', 'green edge'),
                ' may be traded  ·  ', el('span', 'g', 'gold corner'), ' tracked. Tap items to tick them, then use the buttons below.');
              const sums = el('p', 'legend');
              sums.id = 'itemsums';
              const grid = el('div', 'grid');
              grid.id = 'grid';
              const none = el('p', 'muted');
              none.id = 'noitems';
              const more = button('more', '', () => { I.limit += 150; drawGrid(); });
              more.id = 'more';
              const acts = el('div', 'acts');
              const count = el('span', 'count');
              count.id = 'picked';
              acts.appendChild(count);
              for (const [what, label] of [['exclude', 'Exclude'], ['include', 'Include'], ['clear', 'Clear'], ['track', 'Track'],
                  ['untrack', 'Untrack']]) {
                acts.appendChild(button(what === 'exclude' ? 'stop' : what === 'include' ? 'go' : what === 'track' ? 'pick' : '',
                  label, () => itemsDo(what)));
              }
              acts.appendChild(button('', 'All', () => { for (const e of matching()) I.picked.add(e.bare); drawGrid(); }));
              acts.appendChild(button('', 'None', () => { I.picked.clear(); drawGrid(); }));
              own.replaceChildren(bar, legend, sums, grid, none, more, acts);
              drawGrid();
            }

            // ---- Your prices: the items bought and sold at prices the player set, and how each stands
            const P = { data: null };
            async function openPrices() {
              try {
                P.data = await api('prices');
                if (view === 'prices' && !(own.contains(document.activeElement) && document.activeElement.tagName === 'TEXTAREA')) drawPrices();
              } catch (e) { failed(); }
            }
            function drawPrices() {
              const d = P.data;
              if (!d) return;
              const intro = el('p', 'desc', 'Items the bot orders at a price you set and sells at a price you set. One entry each, '
                + 'apart by commas: item, buy price, sell price, all per item. Example: observer top+100 600 900 x15 singles total 1000');
              const area = el('textarea');
              area.value = d.text || '';
              area.setAttribute('aria-label', 'Your own prices');
              area.placeholder = 'diamond 80 120, golden apple 4k 5.2k x3';
              const line = el('div', 'line');
              line.style.margin = '6px 0 10px';
              line.appendChild(button('pick', 'Save', async () => {
                await change('list', { key: 'prices.own', text: area.value });
                area.blur();
                openPrices();
              }));
              const mode=button(d.only?'go':'',d.only?'Flips only: ON':'Flips only: OFF',async()=>{
                if((await change('setting',{key:'prices.only',toggle:'1'}))?.ok)openPrices();
              });
              const parts = [el('h2','title','Flips'),mode,el('p',d.only?'t-good':'t-gold',d.modeDescription||''),intro,area,line];
              for (const p of d.problems || []) parts.push(el('p', 't-bad', 'Not understood: ' + p));
              if (!d.rows || !d.rows.length) {
                parts.push(el('p', 'muted', d.only?'Your Flips list is empty. Add an item; general market scanning stays off.':'Nothing on the list yet. Normal trading is active.'));
              } else {
                const wide = el('div', 'wide');
                wide.tabIndex = 0;
                wide.setAttribute('aria-label', 'Your prices; scroll sideways to see every column');
                const table = el('table');
                const hr = el('tr');
                for (const h of ['Item', 'Buy', 'Sell', 'Order up', 'Bought', 'Spent', 'Paid each', 'Held', 'Worth at your price',
                    'Profit if it sells', 'Market now']) hr.appendChild(el('th', '', h));
                table.appendChild(el('thead')).appendChild(hr);
                const body = table.appendChild(el('tbody'));
                for (const r of d.rows) {
                  const tr = el('tr');
                  const name = el('td');
                  const img = el('img');
                  img.alt = '';
                  img.addEventListener('error', () => img.remove());
                  img.src = '/item/' + r.icon + '.png';
                  name.append(img, document.createTextNode(r.name));
                  tr.append(name, el('td', 't-secondary', r.buy), el('td', 't-gold', r.sell), el('td', 't-secondary', r.order),
                    el('td', '', r.bought), el('td', 't-secondary', r.spent), el('td', 't-secondary', r.each), el('td', '', r.held),
                    el('td', '', r.yours), el('td', 't-' + r.profitTone, r.profit), el('td', 't-' + r.marketTone, r.market));
                  body.appendChild(tr);
                }
                wide.appendChild(table);
                parts.push(wide);
                parts.push(el('p', 'range', 'Bought and spent are counted since the entry was last changed. '
                  + 'Market now is what one sells for by the figures the bot keeps; green means it is at or over your sell price.'));
              }
              own.replaceChildren(...parts);
            }
            
            async function openStaff() {
              try {
                const d = await api('staff');
                if (view !== 'staff') return;
                const response=button('',d.regionSwitch?'Response: Switch region first':'Response: Disconnect immediately',async()=>{
                  if((await change('staff',{action:'response',mode:d.regionSwitch?'disconnect':'switch'}))?.ok)openStaff();
                });
                const parts = [el('h2', 'title', 'Shared hive Staff Watch'),
                  response,el('p','desc','Same staff freshly detected within 2 minutes of confirmed arrival: disconnect. Duplicate in-flight alerts do not count. Escape / reconnect must be enabled.'),
                  el('p', 'desc', d.status + ' · Timezone shown: ' + d.timeZone), el('p', 'desc', d.note),
                  el('p', 'desc', 'Observation coverage: ' + Math.floor(d.watchedMillis / 60000) + ' minutes (overlap merged)')];
                if (!d.fresh) parts.push(el('p', 'bad', 'Current detection is unavailable; history is not live presence.'));
                function table(title, headers, rows, empty) {
                  parts.push(el('h2', 'title', title));
                  if (!rows.length) { parts.push(el('p', 'muted', empty)); return; }
                  const wide = el('div', 'wide');
                  const t = el('table'); const tr = el('tr');
                  for (const label of headers) tr.appendChild(el('th', '', label));
                  const top = el('thead'); top.appendChild(tr); t.appendChild(top);
                  const body = el('tbody');
                  for (const row of rows) { const line = el('tr'); for (const cell of row) line.appendChild(el('td', '', cell)); body.appendChild(line); }
                  t.appendChild(body); wide.appendChild(t); parts.push(wide);
                }
                table('Currently observed staff', ['Staff', 'Presence', 'Observers', 'Mapped area', 'Local evidence'],
                  d.rows.map(r => [r.name, r.presence, r.observer, r.scope, r.region]), 'No current sightings; this does not establish staff are offline.');
                table('Observed activity history (30 days)', ['Staff', 'Intervals', 'Detected time', 'First in history', 'Last seen', 'Usual observed hours'],
                  d.summaries.map(r => [r.name, String(r.intervals), Math.floor(r.observedMillis / 60000) + ' min', new Date(r.firstSeen).toLocaleString(undefined, {timeZone: d.timeZone, month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'}), new Date(r.lastSeen).toLocaleString(undefined, {timeZone: d.timeZone, month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'}), r.usualHours]), 'History starts when the updated clients begin observing.');
                table('Recent sightings', ['Time', 'Staff', 'Activity', 'Observers', 'Local evidence'],
                  d.history.map(r => [new Date(r.at).toLocaleString(undefined, {timeZone: d.timeZone}), r.name, r.event, r.observer, r.region]), 'No sightings recorded yet.');
                own.replaceChildren(...parts);
              } catch (e) { failed(); }
            }

            // ---- Rivals: who else is trading, and one of them opened up
            const R = { data: null, open: '', detail: null, limit: 60 };
            async function openRivals() {
              try {
                if (R.open) {
                  const j = await api('rival', { id: R.open });
                  if (j.ok) {
                    R.detail = j;
                    if (view === 'rivals') drawRival();
                    return;
                  }
                  R.open = '';
                }
                R.data = await api('rivals');
                if (view === 'rivals') drawRivals();
              } catch (e) { failed(); }
            }
            function bars(hourly, nowHour, cls) {
              const box = el('span', cls);
              box.setAttribute('aria-hidden', 'true');
              let max = 1;
              for (const v of hourly || []) max = Math.max(max, v);
              (hourly || []).forEach((v, h) => {
                const bar = el('i', v === 0 ? 'z' : h === nowHour ? 'n' : '');
                bar.style.height = (v === 0 ? 6 : Math.max(14, Math.round(v * 100 / max))) + '%';
                box.appendChild(bar);
              });
              return box;
            }
            function entries(title, block) {
              const card = el('section', 'card');
              card.appendChild(el('h2', 'title', title));
              if (!block.rows || !block.rows.length) {
                const p = el('p', 'desc', block.empty);
                p.style.padding = '0 10px';
                card.appendChild(p);
                return card;
              }
              for (const r of block.rows) {
                const e = el('div', 'entry');
                const top = el('div', 'top');
                top.append(el('span', '', r.name), el('span', 't-' + r.tone, r.value));
                e.append(top, el('small', '', r.note));
                card.appendChild(e);
              }
              return card;
            }
            function drawRivals() {
              const d = R.data;
              if (!d) return;
              const head = el('div', 'headline');
              head.append(el('span', 'what', d.title.charAt(0).toUpperCase() + d.title.slice(1)), el('span', 'stamp', d.stamp));
              const bots = button('', 'Follow known bots (' + d.knownBots + ')', async () => {
                bots.disabled = true;
                const j = await change('knownbots', {});
                if (j && j.ok) bots.textContent = 'Known bots queued; click to retry';
                bots.disabled = !d.knownBots;
              });
              bots.disabled = !d.knownBots;
              head.appendChild(bots);
              const stats = el('div', 'stats');
              for (const c of d.cards || []) {
                const s = el('div', 'stat');
                s.append(el('span', 'label', c.label), el('b', 't-' + c.tone, c.value), el('small', '', c.note));
                stats.appendChild(s);
              }
              const split = el('div', 'split');
              const wide = el('div', 'wide');
              wide.tabIndex = 0;
              wide.setAttribute('aria-label', 'The rivals; scroll sideways to see every column');
              const table = el('table');
              const hr = el('tr');
              for (const h of d.heads || []) hr.appendChild(el('th', '', h));
              table.appendChild(el('thead')).appendChild(hr);
              const body = table.appendChild(el('tbody'));
              for (const r of (d.rivals || []).slice(0, R.limit)) {
                const tr = el('tr', 'go');
                const name = el('td');
                const open = () => { R.open = r.id; own.replaceChildren(el('p', 'muted', 'Reading...')); openRivals(); };
                // The name is the button, for a keyboard; the whole row answers a tap, as it does a click in game.
                name.appendChild(button('link', r.name, e => { e.stopPropagation(); open(); }));
                tr.addEventListener('click', open);
                tr.append(name, el('td', '', r.sales), el('td', 't-secondary', r.items),
                  el('td', r.shared > 0 ? 't-good' : 't-muted', String(r.shared)), el('td', 't-secondary', r.revenue),
                  el('td', 't-muted', r.seen),
                  el('td', r.status === 'active' ? 't-warn' : r.status === 'quiet' ? 't-secondary' : 't-muted', r.status),
                  el('td', r.bot >= 60 ? 't-warn' : 't-secondary', r.bot + '%'));
                tr.appendChild(el('td')).appendChild(bars(r.hourly, d.nowHour, 'spark'));
                body.appendChild(tr);
              }
              wide.appendChild(table);
              const left = el('div');
              left.appendChild(wide);
              const total = (d.rivals || []).length;
              if (!total) left.appendChild(el('p', 'desc', d.followStatus || ('Nobody on the list yet. ' + d.stamp)));
              if (total > R.limit) left.appendChild(button('more', 'Show ' + Math.min(60, total - R.limit) + ' more of ' + total,
                () => { R.limit += 60; drawRivals(); }));
              else if (d.more > 0) left.appendChild(el('p', 'range', d.more + ' more are not shown.'));
              const side = el('div');
              side.append(entries(d.underdog.title, d.underdog), entries('Repeat buyers  ·  who buys from you', d.buyers));
              split.append(left, side);
              own.replaceChildren(head, stats, split);
            }
            function drawRival() {
              const d = R.detail;
              if (!d) return;
              const head = el('div', 'headline');
              head.appendChild(button('', 'Back', () => { R.open = ''; R.detail = null; if (R.data) drawRivals(); openRivals(); }));
              head.appendChild(el('span', 'what', d.title));
              head.appendChild(button('go', 'Include their markets', () => change('rival', { id: d.id, do: 'include' })));
              head.appendChild(button('pick', 'Track their markets', () => change('rival', { id: d.id, do: 'track' })));
              const card = el('section', 'card');
              const detail = el('div', 'detail');
              const kv = el('dl', 'kv');
              for (const p of d.pairs || []) {
                const pair = el('div');
                pair.append(el('dt', '', p.k), el('dd', 't-' + p.tone, p.v));
                kv.appendChild(pair);
              }
              const right = el('div');
              right.appendChild(el('span', 'label', d.strip));
              right.appendChild(bars(d.hourly, d.nowHour, 'strip'));
              const ticks = el('div', 'ticks');
              for (const h of [0, 6, 12, 18]) ticks.appendChild(el('span', '', String(h)));
              right.appendChild(ticks);
              detail.append(kv, right);
              card.appendChild(detail);
              const lessons = el('div', 'lessons');
              lessons.appendChild(el('span', 'label t-gold', 'Lessons  ·  their listing price vs your target on shared markets'));
              for (const l of d.lessons || []) lessons.appendChild(el('p', 't-' + l.tone, l.t));
              card.appendChild(lessons);
              const wide = el('div', 'wide');
              wide.tabIndex = 0;
              wide.setAttribute('aria-label', 'Their trades; scroll sideways to see every column');
              if (!d.flips || !d.flips.length) {
                wide.appendChild(el('p', 'desc', d.empty));
              } else {
                const table = el('table');
                const hr = el('tr');
                for (const h of d.heads || []) hr.appendChild(el('th', '', h));
                table.appendChild(el('thead')).appendChild(hr);
                const body = table.appendChild(el('tbody'));
                for (const f of d.flips) {
                  const tr = el('tr');
                  f.cells.forEach((c, i) => {
                    const td = el('td', 't-' + c.tone);
                    if (i === 0) {
                      const img = el('img');
                      img.alt = '';
                      img.loading = 'lazy';
                      img.addEventListener('error', () => img.remove());
                      img.src = '/item/' + f.icon.split('#')[0] + '.png';
                      td.appendChild(img);
                    }
                    td.appendChild(document.createTextNode(c.t));
                    tr.appendChild(td);
                  });
                  body.appendChild(tr);
                }
                wide.appendChild(table);
              }
              own.replaceChildren(head, card, wide);
            }

            async function refresh() {
              try {
                const r = await fetch('/state', { cache: 'no-store' });
                if (r.status === 401) { location.reload(); return; }
                draw(await r.json());
                await reportNotice();
                if (!view) picture();
              } catch (e) {
                if (!view) put('notice', SILENT);
              }
            }
            if (OWN[(location.hash || '').slice(1)]) show(location.hash.slice(1)); else refresh();
            setInterval(() => {
              refresh();
              if (view === 'staff') openStaff();
              if (view === 'evasion') refreshEvasionHistory();
              // The rivals move by themselves; a setting or a list only when somebody changes it.
              if (++ticks % 4 !== 0) return;
              if (view === 'rivals') openRivals();
              if (view === 'prices') openPrices();
            }, 5000);
            """ + "</script></body></html>";
}
