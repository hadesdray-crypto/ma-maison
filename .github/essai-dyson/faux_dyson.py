# Faux appareils Dyson pour l'essai : un ventilateur (438) et un robot (276), même protocole que les vrais.
import json, time, sys
import paho.mqtt.client as mqtt

journal = open("faux_dyson.txt", "a")
def log(t):
    journal.write(time.strftime("%H:%M:%S ") + t + "\n"); journal.flush()

ventilo = {"fpwr": "OFF", "fnsp": "0004", "auto": "OFF", "oson": "OFF", "nmod": "OFF", "osal": "0045", "osau": "0315"}
robot = {"state": "INACTIVE_CHARGED", "batteryChargeLevel": 87}

def on_connect(c, u, f, rc, *a):
    log("connecté rc=%s" % rc)
    c.subscribe("438/VEN-EU-TEST0001/command")
    c.subscribe("276/ROB-EU-TEST0002/command")

def on_message(c, u, msg):
    p = json.loads(msg.payload.decode())
    log(msg.topic + " " + json.dumps(p))
    if msg.topic.startswith("438/"):
        if p["msg"] == "REQUEST-CURRENT-STATE":
            c.publish("438/VEN-EU-TEST0001/status/current", json.dumps({"msg": "CURRENT-STATE", "product-state": ventilo}))
        elif p["msg"] == "REQUEST-PRODUCT-ENVIRONMENT-CURRENT-SENSOR-DATA":
            c.publish("438/VEN-EU-TEST0001/status/current", json.dumps({"msg": "ENVIRONMENTAL-CURRENT-SENSOR-DATA",
                      "data": {"tact": "2951", "hact": "0048", "pm25": "0007"}}))
        elif p["msg"] == "STATE-SET":
            ventilo.update(p["data"])
    else:
        if p["msg"] == "REQUEST-CURRENT-STATE":
            c.publish("276/ROB-EU-TEST0002/status", json.dumps(dict({"msg": "CURRENT-STATE"}, **robot)))
        elif p["msg"] == "START":
            robot["state"] = "FULL_CLEAN_RUNNING"
        elif p["msg"] == "ABORT":
            robot["state"] = "INACTIVE_CHARGING"

c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2) if hasattr(mqtt, "CallbackAPIVersion") else mqtt.Client()
c.username_pw_set("faux", "faux")
c.on_connect = on_connect
c.on_message = on_message
c.connect("127.0.0.1", 1883)
c.loop_forever()
