// Passerelle Ma Maison : petit serveur local (réseau de la maison uniquement)
// qui donne à l'appli l'accès aux vidéos, photos et musiques du PC.
// Rien ne sort de la maison. Réglages : reglages.json à côté de ce fichier.
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');

const VERSION = 1;
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
    { nom: 'Créations', chemin: 'D:\\Partage\\Creations' }
  ],
  photos: [
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

// Le chemin demandé doit être dans un des dossiers autorisés
function autorise(chemin) {
  const r = reglages();
  const p = path.resolve(chemin).toLowerCase();
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
      case '/racines': {
        const r = reglages();
        const t = q.get('type');
        const lst = (r[t] || []).filter(d => { try { return fs.statSync(d.chemin).isDirectory(); } catch (e) { return false; } });
        return json(res, 200, { racines: lst });
      }
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
