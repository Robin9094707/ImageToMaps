# Simplex ImageToMap

**Bilder & animierte GIFs auf Karten, eigene Blöcke, 3D-Modelle & Möbel – für Java- _und_ Bedrock-Spieler (Geyser/Floodgate). Für Paper 26.2 und neuer.**

> 🇬🇧 English version below → [English](#english)

---

## Inhalt

| Modul | Was es kann |
|---|---|
| 🖼️ **Image → Map** | PNG/JPG/BMP/GIF aus `plugins/SimplexImageToMap/images/` auf beliebig große Kartenwände legen, mit Dithering, Einpass-Modi und unsichtbaren / leuchtenden Rahmen |
| 🎯 **Geführtes Platzieren** | Live-Vorschau mit Partikeln an Wand, Boden oder Decke: **grün** = passt, **rot** = fehlende Blöcke dahinter, **orange** = blockiert. Größe per Schleichen + Scrollen, Bossbar & Actionbar mit allen Infos |
| 🔄 **Live-Refresh** | Datei im `images/`-Ordner ersetzen → alle Anzeigen aktualisieren sich automatisch (oder `/sim refresh <bild>`) |
| 🎞️ **Animierte GIFs** | Echte Framerate, nur geänderte Bildbereiche werden gesendet (Teil-Karten-Pakete), nur an Spieler in Reichweite, eigenes FPS-Limit für Bedrock |
| 🧱 **Custom-Blöcke** | **Ohne Notenblöcke**: massive Blöcke nutzen ungenutzte Pilzblock-Zustände, transparente Blöcke (Glas, Gitter) ungenutzte Chorus-Pflanzen-Zustände. Echte Abbauzeiten, Werkzeuge, Drops, Rezepte, Sounds, deutsche + englische Namen |
| 🪑 **3D-Modelle & Möbel** | Blockbench-Export hochladen → frei platzierbar, drehbar, skalierbar, mit Kollision und **Hinsetzen**. Live-Hologramm-Vorschau beim Platzieren |
| 📦 **Ressourcenpakete automatisch** | Java-Pack wird bei jeder Änderung neu gebaut und **live an alle Java-Spieler gepusht**; Bedrock-Pack wird von der Geyser-Erweiterung beim nächsten Beitritt gesendet. Eingebauter Webserver – kein externes Hosting nötig |
| 🌍 **Deutsch / Englisch** | Spieler mit deutscher Client-Sprache sehen alles auf Deutsch, alle anderen auf Englisch – Chat, Menüs, Formulare, Item- und Blocknamen (auch auf Bedrock) |
| 📱 **Native Menüs** | Java: Truhen-Menüs + native Paper-**Dialoge** (Textfelder, Schalter, Dropdowns, Slider). Bedrock: native **Floodgate/Geyser-Formulare** |
| 🔊 **Sounds** | Feedback-Sounds für alles (konfigurierbar), Sound-Sets pro Block und **eigene .ogg-Sounds** für Blöcke (Java + Bedrock) |

Mitgeliefert: **21 Beispielblöcke** (Rotholz, Ebenholz, Fischgrätparkett, Marmor, schwarzer Marmor, Schiefer, Vulkanziegel, Jade, Mosaikglas, Milchglas, Kupfergitter …), **3 Möbel** (Stuhl zum Hinsetzen, Tisch, leuchtende Lampe), 2 eigene Sounds, ein Poster und ein animiertes Demo-GIF.

---

## Installation

1. **Paper 26.2+** mit **Java 25**.
2. `SimplexImageToMap-x.y.z.jar` → `plugins/`
3. Für Bedrock: **Geyser-Spigot** + **Floodgate** installieren und `SimplexGeyserExtension-x.y.z.jar` nach `plugins/Geyser-Spigot/extensions/` legen.
4. Server starten. Beispiele werden automatisch installiert.
5. **Port 8163 (TCP) freigeben** – darüber laden Spieler das Ressourcenpaket (änderbar in der `config.yml`, oder `resource-pack.external-url` für eigenes Hosting).
6. Empfohlen in `config/paper-global.yml`:
   ```yaml
   block-updates:
     disable-chorus-plant-updates: true
     disable-mushroom-block-updates: true
   ```
   (Das Plugin blockiert die Updates zusätzlich selbst, so ist es aber 100 % stabil.)

> ℹ️ Die neueste Geyser-Version folgt immer der neuesten Java-Version. Läuft dein Server auf einer älteren Version als Geyser erwartet, installiere zusätzlich **ViaVersion** (Geyser weist beim Start darauf hin).

Die Geyser-Erweiterung funktioniert auch, wenn Geyser auf einem **Proxy** (Velocity/Bungee) läuft: in `extensions/simplexgeyser/config.properties` die `pack-url` auf `http://<server>:8163/bedrock/` setzen.

---

## Benutzung

### Menü
`/sim` (Alias `/simplex`, `/itm`) öffnet das Hauptmenü – Java-Spieler bekommen Truhen-Menüs und Dialoge, Bedrock-Spieler native Formulare.

### Bilder
1. Bild nach `plugins/SimplexImageToMap/images/` hochladen (oder `/sim download <url> [name]`).
2. `/sim place <bild>` (oder Menü → Bilder) → auf Wand/Boden/Decke schauen:
   - **Rechtsklick** platzieren · **Linksklick** Rahmenstil (unsichtbar / sichtbar / leuchtend) · **Schleichen + Scrollen** Größe · **F** Einpassung (Einpassen / Füllen / Strecken) · **Schleichen + Rechtsklick** abbrechen
3. Datei überschreiben → wird automatisch live übernommen (`images.auto-refresh`), oder `/sim refresh <bild>`.
4. `/sim display …` bzw. Menü → Anzeigen: verschieben (wieder geführt), Bild/Stil ändern, GIF pausieren, entfernen, teleportieren.
5. Konsole / Befehlsblock: `/sim display create <bild> <b> <h> <welt> <x> <y> <z> <seite> [stil]`

### Custom-Blöcke
- Menü → Custom-Blöcke → **Block erstellen**: ID, Name DE/EN, Beschreibung DE/EN, Typ (massiv / transparent), Texturen (oben/unten/Seiten aus `textures/`), optional 3D-Modell, Härte, Werkzeug, Sound-Set, eigene Platzier-/Abbau-Sounds, Drop.
- **Rezept**: Block → Crafting-Rezept → Items ins 3×3-Raster klicken (werden nur kopiert) → speichern.
- **Löschen**: Block → Löschen → Ersatzblock wählen (z. B. Erde). Alle platzierten Exemplare – auch in nicht geladenen Chunks – werden live ersetzt.
- Alles auch als Datei: `blocks/<id>.yml` (siehe Beispiele) und `/sim reload`.
- Spieler (ohne Admin-Rechte) können die Blöcke ganz normal platzieren, abbauen und craften.

### 3D-Modelle & Möbel
1. In Blockbench modellieren → *Datei → Export → Java Block/Item* → `model.json` + PNG-Texturen nach `plugins/SimplexImageToMap/models/<name>/` hochladen.
2. Menü → 3D-Modelle → **Upload importieren** → Namen, Skalierung, Kollision, Sitzfunktion, Drehschritte einstellen.
3. Item in die Hand nehmen → Hologramm-Vorschau → Rechtsklick platziert. **Schleichen + Schlagen** hebt es wieder auf. Rechtsklick auf Sitzmöbel = hinsetzen.
- Java: Item-Display (beliebige Drehung/Skalierung). Bedrock: unsichtbarer Rüstungsständer mit Custom-Item → das Modell wird automatisch in **Bedrock-Geometrie + Attachable** umgewandelt.

### Ressourcenpakete
- Java: wird beim Beitritt gesendet und bei jeder Änderung **live** neu gepusht.
- Bedrock: Die Geyser-Erweiterung sendet immer das **neueste** Pack beim Beitritt → Bedrock-Spieler müssen nach Änderungen nur neu verbinden.
- Eigene Zusatzdateien: `resourcepack/java/…` und `resourcepack/bedrock/…` werden in die Packs gemischt; `resourcepack/pack.png` als Icon.
- `/sim pack rebuild | send | info`

---

## Berechtigungen

| Permission | Standard | Beschreibung |
|---|---|---|
| `simplex.use` | alle | Menü, Kataloge |
| `simplex.blocks.place` / `simplex.models.place` / `simplex.models.break` | alle | Blöcke & Möbel nutzen |
| `simplex.images` | OP | Bilder-/Anzeigen-Menü |
| `simplex.images.create` | OP | Bilder platzieren, verschieben, entfernen, herunterladen |
| `simplex.blocks.give` / `simplex.models.give` | OP | Items aus dem Menü nehmen |
| `simplex.blocks.manage` / `simplex.models.manage` | OP | Erstellen, bearbeiten, löschen, Rezepte |
| `simplex.admin` | OP | alles (Packs, Einstellungen, Reload) |

---

## Wie es technisch funktioniert

- **Karten-Updates:** Statische Bilder werden einmal in die Karte gezeichnet; Bukkit synchronisiert nur geänderte Bereiche. GIF-Frames werden vorab quantisiert, das geänderte Rechteck zwischen zwei Frames vorberechnet und als **Teil-Kartenpaket** nur an Spieler in Sichtweite gesendet (Vanilla synchronisiert Karten in Rahmen nur alle 10 Ticks). Geyser übersetzt diese Teil-Updates 1:1 für Bedrock. Für Bedrock gibt es ein eigenes FPS-Limit, Keyframes heilen Paketverluste. Gerendert wird asynchron mit Festplatten-Cache.
- **Farben:** Perzeptuelle Farbsuche (Redmean) über eine 64³-Lookup-Tabelle + serpentines Floyd-Steinberg-Dithering, hochwertige progressive Skalierung.
- **Custom-Blöcke ohne Notenblöcke:** Java nutzt Pilzblock- (massiv, 109 Plätze) und Chorus-Pflanzen-Zustände (transparent, 22 Plätze), die in der Welt nicht natürlich vorkommen. Die Geyser-Erweiterung registriert für jeden Platz einen echten Bedrock-Custom-Block. Texturen, Geometrie, Namen und Sounds werden über das jedes Mal neu erzeugte Bedrock-Pack aufgelöst – darum gehen neue Blöcke **ohne Geyser-Neustart**.
- **Abbauzeit:** Während ein Java-Spieler einen Custom-Block abbaut, wird sein `block_break_speed`-Attribut so skaliert, dass Härte und Werkzeug exakt stimmen – der Client sagt die Zeit korrekt vorher.
- **Items:** Alle Custom-Items sind Papier mit `item_model` (`simplex:slot_N`); Geyser bildet das über die Custom-Item-API v2 auf `geyser_custom:simplex_iN` ab.

### Bekannte Grenzen (ehrlich)
- Bedrock kann **keine unsichtbaren Item-Rahmen** darstellen – Bedrock-Spieler sehen dort normale Rahmen.
- Transparente Blöcke auf Java unterstützen nur **voll durchsichtige oder volle Pixel** (kein halbtransparentes Glas), und ihre Hitbox ist die einer Chorus-Pflanze.
- Java-Clients hören beim Abbauen zusätzlich den Holz-Klang des Pilzblocks (clientseitig fest eingebaut); Bedrock nutzt die echten Block-Sounds.
- Neue Blöcke/Modelle sehen Bedrock-Spieler nach dem **Neuverbinden**; Java-Spieler sofort.
- Die Bedrock-Darstellung von 3D-Modellen in der Hand ist eine Näherung (auf dem Boden/platziert passt sie).

---

## Bauen

```bash
./gradlew build        # Java 25
# plugin/build/libs/SimplexImageToMap-*.jar
# geyser-extension/build/libs/SimplexGeyserExtension-*.jar
python3 tools/generate_examples.py   # Beispiel-Texturen/Blöcke/Modelle/Sounds neu erzeugen (Pillow, numpy, ffmpeg)
```
GitHub Actions baut bei jedem Push beide JARs (Artefakt „SimplexImageToMap“); ein Tag `v*` erstellt automatisch ein Release.

---

<a name="english"></a>
## 🇬🇧 English

**Images & animated GIFs on maps, custom blocks, 3D models & furniture – for Java _and_ Bedrock players (Geyser/Floodgate). Paper 26.2+.**

**Features**
- **Image → map walls** from `plugins/SimplexImageToMap/images/` (PNG/JPG/BMP/GIF), dithering, fit/fill/stretch, invisible & glowing frames.
- **Guided placement** with a live particle preview on walls, floors and ceilings – green fits, red = missing support blocks, orange = blocked. Size via sneak + scroll, boss bar + action bar info.
- **Live refresh**: replace a file and every display updates automatically (`/sim refresh <image>`).
- **Smooth animated GIFs**: real frame rate, only changed rectangles are sent (partial map packets), only to nearby players, separate Bedrock FPS cap.
- **Custom blocks without note blocks** (mushroom block states for solid, chorus plant states for transparent blocks), real mining times, tools, drops, recipes, sound sets and custom `.ogg` sounds, English + German names, live deletion (placed blocks are replaced, e.g. with dirt).
- **3D models / furniture** from Blockbench Java exports – rotatable, scalable, collision, **sitting**, live hologram preview. Converted automatically to Bedrock geometry + attachables.
- **Automatic resource packs**: Java pack is pushed live to every Java player on every change; the Geyser extension serves the newest Bedrock pack on join. Built-in web server.
- **German / English**: German clients get German, everybody else English – chat, menus, forms, item & block names (Bedrock too).
- **Native menus**: Java chest menus + Paper dialogs (text fields, toggles, dropdowns, sliders); Bedrock Floodgate/Geyser forms.
- Bundled: 21 example blocks, 3 furniture models, 2 custom sounds, a poster and an animated demo GIF.

**Install**: Paper 26.2+ (Java 25) → put the plugin jar into `plugins/`; for Bedrock add Geyser-Spigot + Floodgate and put `SimplexGeyserExtension-*.jar` into `plugins/Geyser-Spigot/extensions/`. Open TCP port **8163** (pack download). Recommended in `config/paper-global.yml`: `block-updates.disable-mushroom-block-updates: true` and `disable-chorus-plant-updates: true`. If Geyser runs on a proxy set `pack-url` in `extensions/simplexgeyser/config.properties`.

**Commands**: `/sim` (menu), `/sim place <image> [w] [h]`, `/sim refresh <image|all>`, `/sim download <url> [name]`, `/sim display <list|info|create|move|style|pause|resume|remove|tp>`, `/sim block <list|give|create|edit|delete>`, `/sim model <list|give|remove>`, `/sim pack <info|rebuild|send>`, `/sim reload`.

**Known limitations**: Bedrock can't render invisible item frames; transparent blocks on Java support only fully transparent/opaque pixels and use the chorus plant hit box; Java clients additionally hear the mushroom block's wood break sound; Bedrock players see new content after reconnecting; held 3D models on Bedrock are approximated.

License: MIT
