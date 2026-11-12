(() => {
  const $ = (id) => document.getElementById(id);

  function variantMap(run) {
    const m = {};
    for (const v of run.variants || []) m[v.variant] = v;
    return m;
  }

  function num(v) {
    return typeof v === "number" ? v : null;
  }

  function fmt(v) {
    return v == null ? "—" : String(v);
  }

  function shortSha(sha) {
    return sha ? String(sha).slice(0, 7) : "—";
  }

  function pill(event) {
    const e = event || "unknown";
    return `<span class="pill ${e}">${e}</span>`;
  }

  function linkRun(run) {
    const label = run.run_id ? `#${run.run_id}` : "run";
    if (run.run_url) return `<a href="${run.run_url}">${label}</a>`;
    return label;
  }

  function renderStats(latest) {
    const m = variantMap(latest);
    const legacy = m.legacy || {};
    const hardened = m.hardened || {};
    const cells = [
      {
        label: "Legacy grype OS",
        value: fmt(legacy.grype_os),
        hint: legacy.size || "",
      },
      {
        label: "Hardened grype OS",
        value: fmt(hardened.grype_os),
        hint: hardened.size || "",
      },
      {
        label: "App findings",
        value: fmt(legacy.grype_app ?? hardened.grype_app),
        hint: "java-archive (both bases)",
      },
      {
        label: "Grype DB",
        value: (latest.grype_db_built || "—").slice(0, 10),
        hint: latest.generated || "",
      },
    ];
    $("stats").innerHTML = cells
      .map(
        (c) => `<article class="stat">
        <div class="label">${c.label}</div>
        <div class="value">${c.value}</div>
        <div class="hint">${c.hint}</div>
      </article>`
      )
      .join("");
  }

  function seriesValues(runs, variant, field) {
    return runs.map((r) => {
      const v = variantMap(r)[variant];
      return v ? num(v[field]) : null;
    });
  }

  function drawChart(el, runs) {
    const w = 1000;
    const h = 280;
    const pad = { t: 16, r: 16, b: 36, l: 48 };
    const innerW = w - pad.l - pad.r;
    const innerH = h - pad.t - pad.b;

    const legacy = seriesValues(runs, "legacy", "grype_os");
    const hardened = seriesValues(runs, "hardened", "grype_os");
    const vals = [...legacy, ...hardened].filter((x) => x != null);
    const ymax = Math.max(10, ...vals) * 1.1;
    const n = Math.max(runs.length, 1);

    const xAt = (i) => pad.l + (n === 1 ? innerW / 2 : (i / (n - 1)) * innerW);
    const yAt = (v) => pad.t + innerH - (v / ymax) * innerH;

    function path(series, cls) {
      const pts = [];
      series.forEach((v, i) => {
        if (v == null) return;
        pts.push(`${pts.length ? "L" : "M"}${xAt(i).toFixed(1)},${yAt(v).toFixed(1)}`);
      });
      if (!pts.length) return "";
      const dots = series
        .map((v, i) =>
          v == null
            ? ""
            : `<circle class="dot-${cls}" cx="${xAt(i).toFixed(1)}" cy="${yAt(v).toFixed(1)}" r="3.5" />`
        )
        .join("");
      return `<path class="series-${cls}" d="${pts.join(" ")}" />${dots}`;
    }

    const grid = [0, 0.25, 0.5, 0.75, 1]
      .map((f) => {
        const y = pad.t + innerH * (1 - f);
        const label = Math.round(ymax * f);
        return `<line class="gridline" x1="${pad.l}" x2="${w - pad.r}" y1="${y}" y2="${y}" />
          <text class="axis" x="${pad.l - 8}" y="${y + 4}" text-anchor="end">${label}</text>`;
      })
      .join("");

    const xLabels = runs
      .map((r, i) => {
        if (n > 8 && i !== 0 && i !== n - 1 && i % 2 === 1) return "";
        const d = (r.generated || r.created_at || "").slice(5, 10);
        return `<text class="axis" x="${xAt(i)}" y="${h - 10}" text-anchor="middle">${d}</text>`;
      })
      .join("");

    el.innerHTML = `<svg class="chart" viewBox="0 0 ${w} ${h}" role="img" aria-label="grype OS over time">
      ${grid}
      ${path(legacy, "legacy")}
      ${path(hardened, "hardened")}
      ${xLabels}
    </svg>`;
  }

  function localTable(run) {
    const rows = (run.variants || [])
      .map(
        (v) => `<tr>
        <td><code>${v.variant}</code></td>
        <td class="num">${fmt(v.size)}</td>
        <td class="num">${fmt(v.grype_total)}</td>
        <td class="num">${fmt(v.grype_os)}</td>
        <td class="num">${fmt(v.grype_app)}</td>
        <td class="num">${fmt(v.grype_crit)}/${fmt(v.grype_high)}</td>
        <td class="num">${fmt(v.trivy_total)}</td>
      </tr>`
      )
      .join("");
    return `<table>
      <thead><tr>
        <th>Variant</th><th>Size</th><th>grype</th><th>OS</th><th>app</th><th>c/h</th><th>trivy</th>
      </tr></thead>
      <tbody>${rows || '<tr><td colspan="7" class="empty">No variants</td></tr>'}</tbody>
    </table>`;
  }

  function quayTable(run) {
    const tags = (run.quay && run.quay.tags) || [];
    if (!tags.length) return `<p class="empty">No Quay summary in this artifact.</p>`;
    const rows = tags
      .map(
        (t) => `<tr>
        <td><code>${t.tag}</code></td>
        <td>${fmt(t.status)}</td>
        <td class="num">${fmt(t.total)}</td>
        <td class="num">${fmt(t.critical)}</td>
        <td class="num">${fmt(t.high)}</td>
      </tr>`
      )
      .join("");
    return `<table>
      <thead><tr><th>Tag</th><th>Status</th><th>Total</th><th>Crit</th><th>High</th></tr></thead>
      <tbody>${rows}</tbody>
    </table>`;
  }

  function twinsNote(run) {
    const m = variantMap(run);
    const pairs = [
      ["legacy", "legacy-lightwell"],
      ["hardened", "hardened-lightwell"],
    ];
    const same = pairs.every(([a, b]) => {
      if (!m[a] || !m[b]) return true;
      return (
        m[a].grype_os === m[b].grype_os &&
        m[a].grype_app === m[b].grype_app &&
        m[a].grype_total === m[b].grype_total
      );
    });
    if (!same) return "";
    return `<p class="sub">Lightwell rows twin Central on this run — expected on <code>validated</code> (provenance). Look at legacy→hardened OS for the Hardened Images effect.</p>`;
  }

  function historyTable(runs) {
    const body = [...runs]
      .reverse()
      .map((r) => {
        const m = variantMap(r);
        return `<tr>
          <td>${linkRun(r)} ${pill(r.event)}</td>
          <td class="num">${(r.generated || "").replace("T", " ").replace("Z", "")}</td>
          <td><code>${shortSha(r.sha)}</code></td>
          <td class="num">${fmt(m.legacy && m.legacy.grype_os)}</td>
          <td class="num">${fmt(m.hardened && m.hardened.grype_os)}</td>
          <td class="num">${fmt(m.legacy && m.legacy.grype_app)}</td>
          <td class="num">${(r.grype_db_built || "—").slice(0, 10)}</td>
        </tr>`;
      })
      .join("");
    return `<table>
      <thead><tr>
        <th>Run</th><th>Generated</th><th>SHA</th>
        <th>Legacy OS</th><th>Hardened OS</th><th>App</th><th>Grype DB</th>
      </tr></thead>
      <tbody>${body}</tbody>
    </table>`;
  }

  async function main() {
    const res = await fetch("data/history.json", { cache: "no-store" });
    if (!res.ok) throw new Error(`history.json HTTP ${res.status}`);
    const data = await res.json();
    const runs = data.runs || [];
    $("disclaimer").textContent = data.disclaimer || "";
    $("meta").innerHTML = `
      <span>Updated ${data.updated || "—"}</span>
      <span>${runs.length} run${runs.length === 1 ? "" : "s"} in history</span>
    `;
    if (!runs.length) {
      $("stats").innerHTML = `<div class="empty">No runs yet.</div>`;
      return;
    }
    const latest = runs[runs.length - 1];
    renderStats(latest);
    drawChart($("chart-os"), runs);
    $("latest-sub").innerHTML = `${linkRun(latest)} · ${pill(latest.event)} · sha <code>${shortSha(latest.sha)}</code> · generated ${latest.generated || "—"}`;
    $("latest-local").innerHTML = twinsNote(latest) + localTable(latest);
    $("latest-quay").innerHTML = quayTable(latest);
    $("history-table").innerHTML = historyTable(runs);
  }

  main().catch((err) => {
    $("disclaimer").textContent = `Failed to load history: ${err.message}`;
  });
})();
