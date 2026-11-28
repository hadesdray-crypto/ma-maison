// Passerelle Ma Maison : petit serveur local (réseau de la maison uniquement)
// qui donne à l'appli l'accès aux vidéos, photos et musiques du PC.
// Rien ne sort de la maison. Réglages : reglages.json à côté de ce fichier.
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFile } = require('child_process');

const VERSION = 3;
const PORT = 8790;
const DOSSIER = __dirname;
const FICHIER_REGLAGES = path.join(DOSSIER, 'reglages.json');
const JOURNAL = path.join(DOSSIER, 'journal.txt');

function ecrireJournal(t) {
  try {
    const ligne = new Date().toISOString() + ' ' + t + '\n';
    if (fs.existsSync(JOURNAL) && fs.statSync(JOURNAL).size > 500000) fs.writeFileSync(JOURNAL, '');
    fs.appendFileSync(JOURNAL, ligne);
  } catch (e) { /* rien */ }
}

const REGLAGES_DEFAUT = {
  videos: [
    { nom: 'Films 2022', chemin: 'D:\\filmes 2022' },
    { nom: 'Films 2023', chemin: 'D:\\filmes 2023' },
    { nom: 'Vidéos', chemin: path.join(os.homedir(), 'Videos') },
    { nom: 'Vidéos D', chemin: 'D:\\video' },
    { nom: 'Créations', chemin: 'D:\\Partage\\Creations' }
  ],
  photos: [
    { nom: 'Photos', chemin: 'D:\\photos' },
    { nom: 'Images', chemin: path.join(os.homedir(), 'Pictures') },
    { nom: 'Créations', chemin: 'D:\\Partage\\Creations' }
  ],
  musique: [
    { nom: 'Musique', chemin: 'D:\\musique' },
    { nom: 'Mes musiques', chemin: path.join(os.homedir(), 'Music', 'musiques') },
    { nom: 'Chansons', chemin: 'D:\\Partage\\chansons' }
  ],
  moteur: { adresse: '', jeton: '' }
};

function reglages() {
  try {
    return Object.assign({}, REGLAGES_DEFAUT, JSON.parse(fs.readFileSync(FICHIER_REGLAGES, 'utf8').replace(/^\uFEFF/, '')));
  } catch (e) {
    try { fs.writeFileSync(FICHIER_REGLAGES, JSON.stringify(REGLAGES_DEFAUT, null, 2)); } catch (e2) { /* rien */ }
    return REGLAGES_DEFAUT;
  }
}

const EXT = {
  videos: ['.mp4', '.mkv', '.webm', '.mov', '.m4v', '.avi', '.ts', '.3gp', '.wmv', '.flv', '.mpg', '.mpeg'],
  photos: ['.jpg', '.jpeg', '.png', '.webp', '.gif', '.bmp', '.heic'],
  musique: ['.mp3', '.m4a', '.aac', '.ogg', '.opus', '.flac', '.wav', '.wma']
};
const TYPES = {
  '.mp4': 'video/mp4', '.m4v': 'video/mp4', '.mkv': 'video/x-matroska', '.webm': 'video/webm', '.mov': 'video/quicktime',
  '.avi': 'video/x-msvideo', '.ts': 'video/mp2t', '.3gp': 'video/3gpp', '.wmv': 'video/x-ms-wmv', '.flv': 'video/x-flv',
  '.mpg': 'video/mpeg', '.mpeg': 'video/mpeg',
  '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.webp': 'image/webp', '.gif': 'image/gif', '.bmp': 'image/bmp', '.heic': 'image/heic',
  '.mp3': 'audio/mpeg', '.m4a': 'audio/mp4', '.aac': 'audio/aac', '.ogg': 'audio/ogg', '.opus': 'audio/ogg', '.flac': 'audio/flac', '.wav': 'audio/wav', '.wma': 'audio/x-ms-wma'
};

function adresseLocale(ip) {
  ip = (ip || '').replace(/^::ffff:/, '');
  if (ip === '127.0.0.1' || ip === '::1') return true;
  if (/^192\.168\./.test(ip) || /^10\./.test(ip) || /^169\.254\./.test(ip)) return true;
  const m = /^172\.(\d+)\./.exec(ip);
  return !!(m && +m[1] >= 16 && +m[1] <= 31);
}

// ---------- disques du PC ----------
// Le disque de Windows (C:) n'est jamais ouvert en entier : seuls ses dossiers de médias réglés le sont.
const SYSTEME = (process.env.SystemDrive || 'C:').toUpperCase();
const CACHES = ['system volume information', 'recovery', 'config.msi', 'windowsapps', 'wpsystem', 'program files',
  'program files (x86)', 'programdata', 'msocache', 'msdownld.tmp', 'windows', 'boot', 'perflogs'];
let disquesVus = [];

function lettreDe(chemin) {
  const m = /^([a-zA-Z]):/.exec(chemin || '');
  return m ? m[1].toUpperCase() + ':' : '';
}

// Disques présents (Windows) : lettre, nom, taille
function lireDisquesWindows() {
  return new Promise(ok => {
    if (process.platform !== 'win32') return ok(null);
    const cmd = 'Get-CimInstance Win32_LogicalDisk | ForEach-Object { $_.DeviceID + "|" + $_.VolumeName + "|" + $_.DriveType + "|" + $_.Size }';
    execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', cmd], { timeout: 10000, windowsHide: true }, (err, sortie) => {
      if (err) return ok(null);
      const l = [];
      for (const ligne of String(sortie).split(/\r?\n/)) {
        const [lettre, nom, genre, taille] = ligne.trim().split('|');
        if (!/^[A-Z]:$/.test(lettre || '')) continue;
        if (genre !== '2' && genre !== '3') continue; // 2 = disque amovible, 3 = disque interne ou externe
        l.push({ lettre, nom: (nom || '').trim(), taille: Number(taille) || 0 });
      }
      ok(l);
    });
  });
}

function nomDisque(d) {
  const go = d.taille ? Math.round(d.taille / 1e9) : 0;
  const t = go >= 1000 ? (Math.round(go / 100) / 10) + ' To' : go > 0 ? go + ' Go' : '';
  return [d.nom, t].filter(Boolean).join(' · ');
}

// Les disques déjà vus restent connus même débranchés (gardés dans reglages.json)
async function disques() {
  const presents = await lireDisquesWindows();
  let r;
  try { r = JSON.parse(fs.readFileSync(FICHIER_REGLAGES, 'utf8').replace(/^\uFEFF/, '')); } catch (e) { r = null; }
  const connus = (r && Array.isArray(r.disques_connus)) ? r.disques_connus : [];
  let change = false;
  if (presents) {
    for (const d of presents) {
      const nom = nomDisque(d);
      const k = connus.find(x => x.lettre === d.lettre);
      if (!k) { connus.push({ lettre: d.lettre, nom }); change = true; }
      else if (nom && k.nom !== nom) { k.nom = nom; change = true; }
    }
    // Les autres réglages du fichier sont conservés tels quels
    if (change && r) {
      r.disques_connus = connus;
      try { fs.writeFileSync(FICHIER_REGLAGES, JSON.stringify(r, null, 2)); } catch (e) { /* rien */ }
    }
  }
  disquesVus = connus.map(k => ({
    lettre: k.lettre,
    nom: k.nom || '',
    present: presents ? presents.some(d => d.lettre === k.lettre) : null
  }));
  return disquesVus;
}

// État d'un dossier, sans jamais bloquer le serveur (un disque en veille peut mettre plusieurs secondes à répondre)
function etat(chemin, ms) {
  return new Promise(ok => {
    let fini = false;
    const t = setTimeout(() => { if (!fini) { fini = true; ok('veille'); } }, ms);
    fs.stat(chemin, (e, st) => {
      if (fini) return;
      fini = true;
      clearTimeout(t);
      if (!e) return ok(st.isDirectory() ? 'ok' : 'absent');
      ok(e.code === 'ENOENT' || e.code === 'ENOTDIR' ? 'absent' : (e.code === 'EACCES' || e.code === 'EPERM') ? 'refuse' : 'illisible');
    });
  });
}

const RAISONS = {
  absent: 'Introuvable : disque débranché, ou dossier déplacé',
  veille: 'Le disque ne répond pas (en veille ?) : réessaie dans quelques secondes',
  refuse: 'Accès refusé par Windows',
  illisible: 'Disque illisible'
};

async function racines(res, type) {
  const r = reglages();
  const liste = await disques();
  const lst = [];
  const verifs = (r[type] || []).map(d => etat(d.chemin, 15000).then(e => ({ d, e })));
  for (const { d, e } of await Promise.all(verifs)) {
    const l = lettreDe(d.chemin);
    const k = liste.find(x => x.lettre === l);
    let raison = e === 'ok' ? '' : RAISONS[e];
    if (e === 'absent' && k && k.present === false) raison = 'Disque ' + l + ' débranché ou éteint';
    lst.push({ nom: d.nom, chemin: d.chemin, connecte: e === 'ok', raison, disque: l, nomDisque: k ? k.nom : '' });
  }
  // Chaque disque (sauf celui de Windows) peut aussi s'ouvrir en entier
  const entiers = liste.filter(k => k.lettre !== SYSTEME).map(k => etat(k.lettre + '\\', 15000).then(e => ({ k, e })));
  for (const { k, e } of await Promise.all(entiers)) {
    lst.push({
      nom: 'Tout le disque ' + k.lettre, chemin: k.lettre + '\\', connecte: e === 'ok',
      raison: e === 'ok' ? '' : (e === 'absent' ? 'Disque ' + k.lettre + ' débranché ou éteint' : RAISONS[e]),
      disque: k.lettre, nomDisque: k.nom, entier: true
    });
  }
  json(res, 200, { racines: lst, version: VERSION });
}

// Le chemin demandé doit être dans un des dossiers autorisés, ou sur un disque connu autre que celui de Windows
function autorise(chemin) {
  const r = reglages();
  const p = path.resolve(chemin).toLowerCase();
  const l = lettreDe(chemin);
  if (l && l !== SYSTEME && disquesVus.some(k => k.lettre === l)) return true;
  for (const t of ['videos', 'photos', 'musique']) {
    for (const d of r[t] || []) {
      const base = path.resolve(d.chemin).toLowerCase();
      if (p === base || p.startsWith(base + path.sep)) return true;
    }
  }
  return false;
}

function json(res, code, obj) {
  const corps = Buffer.from(JSON.stringify(obj), 'utf8');
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': corps.length, 'Cache-Control': 'no-store' });
  res.end(corps);
}

function liste(res, chemin, type) {
  if (!autorise(chemin)) return json(res, 403, { erreur: 'Dossier non autorisé' });
  fs.readdir(chemin, { withFileTypes: true }, (err, items) => {
    if (err) return json(res, 404, { erreur: 'Dossier introuvable' });
    const exts = EXT[type] || [].concat(EXT.videos, EXT.photos, EXT.musique);
    const dossiers = [];
    const fichiers = [];
    for (const it of items) {
      if (it.name.startsWith('.') || it.name.startsWith('$')) continue;
      if (CACHES.includes(it.name.toLowerCase())) continue;
      const complet = path.join(chemin, it.name);
      if (it.isDirectory()) dossiers.push({ nom: it.name, chemin: complet });
      else if (exts.includes(path.extname(it.name).toLowerCase())) {
        let taille = 0;
        try { taille = fs.statSync(complet).size; } catch (e) { /* rien */ }
        fichiers.push({ nom: it.name, chemin: complet, taille });
      }
    }
    const tri = (a, b) => a.nom.localeCompare(b.nom, 'fr', { numeric: true, sensitivity: 'base' });
    dossiers.sort(tri);
    fichiers.sort(tri);
    json(res, 200, { dossiers, fichiers });
  });
}

function fichier(req, res, chemin) {
  if (!autorise(chemin)) { res.writeHead(403); return res.end(); }
  fs.stat(chemin, (err, st) => {
    if (err || !st.isFile()) { res.writeHead(404); return res.end(); }
    const type = TYPES[path.extname(chemin).toLowerCase()] || 'application/octet-stream';
    // Fichier vide : on répond tout de suite (sinon la lecture plantait tout le serveur)
    if (st.size === 0) {
      res.writeHead(200, { 'Content-Type': type, 'Content-Length': 0 });
      return res.end();
    }
    const plage = req.headers.range;
    let debut = 0;
    let fin = st.size - 1;
    let code = 200;
    if (plage) {
      const m = /bytes=(\d*)-(\d*)/.exec(plage);
      if (m) {
        if (m[1] !== '') debut = parseInt(m[1], 10);
        if (m[2] !== '') fin = Math.min(parseInt(m[2], 10), st.size - 1);
        if (m[1] === '' && m[2] !== '') { debut = Math.max(0, st.size - parseInt(m[2], 10)); fin = st.size - 1; }
        if (debut > fin || debut >= st.size) {
          res.writeHead(416, { 'Content-Range': 'bytes */' + st.size });
          return res.end();
        }
        code = 206;
      }
    }
    const entetes = { 'Content-Type': type, 'Accept-Ranges': 'bytes', 'Content-Length': fin - debut + 1 };
    if (code === 206) entetes['Content-Range'] = 'bytes ' + debut + '-' + fin + '/' + st.size;
    res.writeHead(code, entetes);
    if (req.method === 'HEAD') return res.end();
    const flux = fs.createReadStream(chemin, { start: debut, end: fin, highWaterMark: 256 * 1024 });
    flux.on('error', () => res.destroy());
    res.on('close', () => flux.destroy());
    flux.pipe(res);
  });
}

const serveur = http.createServer((req, res) => {
  if (!adresseLocale(req.socket.remoteAddress)) { res.writeHead(403); return res.end(); }
  let u;
  try { u = new URL(req.url, 'http://local'); } catch (e) { res.writeHead(400); return res.end(); }
  const q = u.searchParams;
  try {
    switch (u.pathname) {
      case '/ping':
        return json(res, 200, { nom: 'passerelle-maison', version: VERSION, pc: os.hostname() });
      case '/racines':
        return racines(res, q.get('type')).catch(e => {
          ecrireJournal('racines : ' + e.message);
          json(res, 500, { erreur: 'Erreur de la passerelle' });
        });
      case '/liste':
        return liste(res, q.get('chemin') || '', q.get('type') || '');
      case '/fichier':
        return fichier(req, res, q.get('chemin') || '');
      case '/moteur':
        return json(res, 200, reglages().moteur || {});
      default:
        res.writeHead(404);
        return res.end();
    }
  } catch (e) {
    ecrireJournal('erreur ' + e.message);
    try { json(res, 500, { erreur: 'Erreur de la passerelle' }); } catch (e2) { /* rien */ }
  }
});

serveur.on('error', e => {
  ecrireJournal('arrêt : ' + e.message);
  process.exit(1);
});
serveur.listen(PORT, '0.0.0.0', () => ecrireJournal('passerelle démarrée sur le port ' + PORT));
reglages();
disques().catch(() => {});
