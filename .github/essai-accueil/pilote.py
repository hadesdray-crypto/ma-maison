# Pilote l'appli sur l'appareil virtuel : touche un texte, appui long, télécommande, captures.
import subprocess, sys, re, time
def adb(*a): return subprocess.run(["adb"] + list(a), capture_output=True, text=True).stdout
m = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size")); L, H = int(m.group(1)), int(m.group(2))
rot = adb("shell", "dumpsys", "input")
if "SurfaceOrientation: 1" in rot or "SurfaceOrientation: 3" in rot: L, H = H, L
def ecran():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return adb("shell", "cat", "/sdcard/ui.xml")
def glisser(bas=True):
    a, b = (int(H * 0.72), int(H * 0.38)) if bas else (int(H * 0.38), int(H * 0.72))
    adb("shell", "input", "swipe", str(L // 2), str(a), str(L // 2), str(b), "400"); time.sleep(1)
def trouver(texte, exact, defile=6):
    for _ in range(defile + 1):
        x = ecran()
        for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
            if (m.group(1) == texte) if exact else m.group(1).startswith(texte):
                x1, y1, x2, y2 = map(int, m.groups()[1:])
                return (x1 + x2) // 2, (y1 + y2) // 2
        glisser()
    print("INTROUVABLE :", texte); return None
def toucher(texte, exact=False, long=False):
    p = trouver(texte, exact)
    if not p: return False
    if long: adb("shell", "input", "swipe", str(p[0]), str(p[1]), str(p[0]), str(p[1]), "900")
    else: adb("shell", "input", "tap", str(p[0]), str(p[1]))
    time.sleep(2); return True
def capture(nom):
    adb("shell", "screencap", "-p", "/sdcard/c.png"); adb("pull", "/sdcard/c.png", nom + ".png")
def textes():
    return re.findall(r'text="([^"]+)"', ecran())
def focus():
    x = ecran()
    m = re.search(r'<node[^>]*focused="true"[^>]*>', x)
    if not m: return ""
    t = re.search(r'text="([^"]*)"', m.group(0))
    if t and t.group(1): return t.group(1)
    s = re.search(r'<node[^>]*selected="true"[^>]*>', x[m.end():])
    zone = x[m.end() + (s.end() if s else 0) - (len(s.group(0)) if s else 0):]
    t = re.search(r'text="([^"]+)"', zone)
    return t.group(1) if t else ""
def touche(code): adb("shell", "input", "keyevent", str(code)); time.sleep(0.7)
def aller(texte):
    # Télécommande : parcourt l'écran rangée par rangée jusqu'à ce que l'élément choisi soit le bon
    for _ in range(14):
        if focus().startswith(texte): return True
        for _ in range(8):
            touche(22)
            if focus().startswith(texte): return True
        for _ in range(8): touche(21)
        touche(20)
    print("INATTEIGNABLE à la télécommande :", texte); return False
for e in sys.argv[1:]:
    if e.startswith("tapx:"): print("toucher exact", e[5:], toucher(e[5:], exact=True))
    elif e.startswith("tap:"): print("toucher", e[4:], toucher(e[4:]))
    elif e.startswith("long:"): print("appui long", e[5:], toucher(e[5:], exact=True, long=True))
    elif e.startswith("cap:"): capture(e[4:]); print("capture", e[4:], textes())
    elif e.startswith("dormir:"): time.sleep(int(e[7:]))
    elif e.startswith("touche:"):
        code, _, n = e[7:].partition("x")
        for _ in range(int(n or 1)): adb("shell", "input", "keyevent", code); time.sleep(0.6)
        time.sleep(1)
    elif e == "appuilong": adb("shell", "input", "keyevent", "--longpress", "23"); time.sleep(2)
    elif e == "retour": adb("shell", "input", "keyevent", "4"); time.sleep(2)
    elif e == "bas": glisser()
    elif e == "haut":
        for _ in range(6): glisser(False)
    elif e == "focus": print("élément choisi :", focus())
    elif e.startswith("aller:"): print("télécommande vers", e[6:], aller(e[6:]))
