# Simplex ImageToMap

**Bilder & animierte GIFs auf Kartenwänden – mit geführtem Platzieren und Live-Vorschau. Für Java- _und_ Bedrock-Spieler (Geyser/Floodgate). Paper 26.2 und neuer.**

> 🇬🇧 English version below → [English](#english)

---

## Features

| | |
|---|---|
| 🖼️ **Bilder auf Karten** | PNG, JPG, BMP und GIF aus `plugins/SimplexImageToMap/images/` auf beliebig große Kartenwände (bis 32×32 Karten). Hochwertige Skalierung, wahrnehmungsgerechte Farbwahl und Dithering. Einpass-Modi: Einpassen, Füllen, Strecken |
| 🎯 **Geführtes Platzieren** | Live-Vorschau mit Partikeln an Wand, Boden oder Decke. **Grün** heißt passt, **rot** heißt fehlende Blöcke dahinter, **orange** heißt blockiert. Ein Pfeil zeigt, wo oben ist. Bossbar und Actionbar zeigen alle Infos |
| 🔄 **Live-Refresh** | Ersetzt du eine Datei im `images/`-Ordner, aktualisieren sich alle Anzeigen automatisch. Alternativ: `/sim refresh <bild>` |
| 🎞️ **Animierte GIFs** | Laufen in echter Bildrate. Gesendet werden nur die geänderten Bildbereiche (Teil-Karten-Pakete) und nur an Spieler in Reichweite. Bedrock hat ein eigenes FPS-Limit |
| 🧹 **Entfernen leicht gemacht** | Über das Menü lässt sich eine Anzeige entfernen, alle Anzeigen eines Bildes entfernen oder ein Bild samt Anzeigen löschen (die Datei landet im Papierkorb). Admins können per **Schleichen + Rahmen schlagen** direkt das Menü der Anzeige öffnen |
| 🪟 **Rahmenstile** | Unsichtbar, sichtbar, leuchtend oder leuchtend-unsichtbar. Rahmen sind fixiert und unzerstörbar |
| 📱 **Native Menüs** | Java: Truhen-Menüs und native Paper-**Dialoge** mit Textfeldern, Schaltern, Dropdowns und Slidern. Bedrock: native **Floodgate/Geyser-Formulare** |
| 🌍 **Deutsch / Englisch** | Spieler mit deutscher Client-Sprache sehen alles auf Deutsch, alle anderen auf Englisch |
| 🔊 **Sounds** | Feedback-Sounds für Menüs, Vorschau, Platzieren und Fehler, alle in der `config.yml` einstellbar |

Mitgeliefert werden ein Beispiel-Poster und ein animiertes Demo-GIF.

---

## Installation

1. **Paper 26.2+** mit **Java 25**.
2. `SimplexImageToMap-x.y.z.jar` nach `plugins/` kopieren.
3. Optional für Bedrock-Menüs: **Floodgate** (oder Geyser) installieren. Die Bilder selbst sehen Bedrock-Spieler über Geyser auch ohne Floodgate.
4. Server neu starten.

Es gibt **keinen zusätzlichen Port** und **kein Ressourcenpaket**. Alles läuft über ganz normale Karten.

---

## Benutzung

`/sim` öffnet das Menü. Die Aliase `/simplex`, `/itm` und `/imagetomap` machen dasselbe.

### Bild aufhängen
1. Bild nach `plugins/SimplexImageToMap/images/` hochladen, oder `/sim download <url> [name]` benutzen.
2. `/sim place <bild>` eingeben oder im Menü unter **Bilder → Bild → Platzieren**. Dann auf eine Wand, den Boden oder die Decke schauen:
   - **Rechtsklick**: platzieren
   - **Linksklick**: Rahmenstil wechseln
   - **Schleichen + Scrollen**: Größe ändern, das Seitenverhältnis bleibt erhalten
   - **F**: Einpassung wechseln (Einpassen / Füllen / Strecken)
   - **Schleichen + Rechtsklick**: abbrechen
   - Bedrock: Tippen = platzieren, Schlagen = Stil, Schleichen + Hotbar-Slot wechseln = Größe
3. Mit **„Mit Optionen platzieren…“** stellst du Größe, Einpassung, Rahmenstil und Dithering vorher im Formular ein.

### Bearbeiten & entfernen
- **Schleichen + Rahmen schlagen** (Admin) öffnet das Menü der Anzeige. Dort kannst du sie teleportieren, aktualisieren, Bild oder Stil ändern, sie geführt verschieben, ein GIF pausieren oder sie entfernen.
- **Bilder → Bild → Alle Anzeigen entfernen** entfernt alle Anzeigen dieses Bildes. Die Datei bleibt erhalten.
- **Bilder → Bild → Bild löschen** löscht die Datei und alle Anzeigen. Die Datei wird nach `trash/images/` verschoben.
- Per Befehl: `/sim display remove [id]` oder `/sim image delete <bild>`.

### Alle Befehle
| Befehl | |
|---|---|
| `/sim` | Menü |
| `/sim place <bild> [breite] [höhe]` · `cancel` · `size <b> <h>` | Geführtes Platzieren |
| `/sim refresh <bild\|all>` | Bild live neu laden |
| `/sim download <url> [name]` | Bild oder GIF herunterladen |
| `/sim image delete <bild>` | Bild und alle Anzeigen löschen |
| `/sim display list\|info\|move\|style <stil>\|pause\|resume\|remove\|tp [id]` | Anzeigen verwalten |
| `/sim display create <bild> <b> <h> <welt> <x> <y> <z> <seite> [stil]` | Platzieren aus Konsole oder Befehlsblock |
| `/sim reload` | Neu laden |

### Berechtigungen
| Permission | Standard | |
|---|---|---|
| `simplex.use` | alle | `/sim` benutzen |
| `simplex.images` | OP | Bilder- und Anzeigen-Menü |
| `simplex.images.create` | OP | Platzieren, verschieben, entfernen, herunterladen, löschen |
| `simplex.admin` | OP | Alles, inklusive Einstellungen |

---

## Technik

- **Statische Bilder** werden einmal in die Karte gezeichnet. Bukkit synchronisiert danach nur geänderte Bereiche.
- **GIFs** werden vorab quantisiert. Das geänderte Rechteck zwischen zwei Frames wird vorberechnet und als Teil-Kartenpaket nur an Spieler in Sichtweite gesendet. Vanilla aktualisiert Karten in Rahmen nur alle 10 Ticks. Geyser übersetzt die Teil-Updates direkt für Bedrock. Bedrock hat ein eigenes FPS-Limit, und Keyframes gleichen Paketverluste aus.
- **Rendering** läuft asynchron mit Festplatten-Cache. Nach einem Neustart sind die Anzeigen sofort wieder da.
- **Farben**: Redmean-Farbabstand über eine 64³-Lookup-Tabelle, serpentines Floyd-Steinberg-Dithering und progressive Skalierung.

**Grenze:** Bedrock kann keine unsichtbaren Item-Rahmen darstellen. Bedrock-Spieler sehen dort normale Rahmen.

## Bauen
```bash
./gradlew build      # Java 25 → plugin/build/libs/SimplexImageToMap-*.jar
```
GitHub Actions baut bei jedem Push das JAR (Artefakt). Ein Tag `v*` erstellt automatisch ein Release.

---

<a name="english"></a>
## 🇬🇧 English

**Images & animated GIFs on map walls with guided placement and live preview – for Java and Bedrock (Geyser/Floodgate). Paper 26.2+.**

- **Guided placement:** a live particle preview on walls, floors and ceilings. Green means it fits, red means support blocks are missing, orange means blocked. Sneak + scroll changes the size, left click changes the frame style, F changes the fit mode.
- **Live refresh:** replace a file in `images/` and every display updates. `/sim refresh <image>` does the same.
- **Smooth GIFs:** partial map packets, sent only to nearby players, with a separate Bedrock FPS cap.
- **Easy removal:** remove one display, all displays of an image, or delete an image together with its displays (the file goes to `trash/images/`). Admins can sneak + hit a frame to open its display menu.
- **Menus:** Java chest menus and Paper dialogs, native Bedrock forms (Floodgate/Geyser). Texts in German or English depending on the client language.
- **No extra port and no resource pack needed.**

**Commands:** `/sim`, `/sim place <image> [w] [h]`, `/sim refresh <image|all>`, `/sim download <url> [name]`, `/sim image delete <image>`, `/sim display <list|info|create|move|style|pause|resume|remove|tp>`, `/sim reload`.

License: MIT
