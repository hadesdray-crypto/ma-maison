# Pilote l'appli sur le téléphone virtuel : trouve un texte à l'écran et le touche, prend des captures.
import subprocess, sys, re, time
def adb(*a): return subprocess.run(["adb"] + list(a), capture_output=True, text=True).stdout
def ecran():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return adb("shell", "cat", "/sdcard/ui.xml")
def toucher(texte, defile=6):
    for _ in range(defile + 1):
        x = ecran()
        for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
            if m.group(1).startswith(texte):
                x1, y1, x2, y2 = map(int, m.groups()[1:])
                adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                time.sleep(2); return True
        adb("shell", "input", "swipe", "500", "1500", "500", "700", "400"); time.sleep(1)
    print("INTROUVABLE :", texte); return False
def capture(nom):
    adb("shell", "screencap", "-p", "/sdcard/c.png"); adb("pull", "/sdcard/c.png", nom + ".png")
def textes():
    return re.findall(r'text="([^"]+)"', ecran())
etapes = sys.argv[1:]
for e in etapes:
    if e.startswith("tap:"): print("toucher", e[4:], toucher(e[4:]))
    elif e.startswith("cap:"): capture(e[4:]); print("capture", e[4:], textes())
    elif e.startswith("dormir:"): time.sleep(int(e[7:]))
    elif e == "retour": adb("shell", "input", "keyevent", "4"); time.sleep(2)
    elif e == "haut":
        for _ in range(6): adb("shell", "input", "swipe", "500", "700", "500", "1600", "300")
