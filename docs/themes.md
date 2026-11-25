---
layout: doc
title: Themes
---

# Themes

A theme sets the colours, corner radius, line weight and a few element styles of OpenTRAP. It is one plain text file. Light and dark are themes too, and a theme you write names one of them as its **base** and sets only what it wants to change; every other key comes from the base.

OpenTRAP ships these themes: Light, Dark, High Contrast, Linen, Deep Sea, Ember, S1 Light, S1 Dark and Phosphor. Linen is the theme a new installation starts in; a theme you have chosen stays chosen. Choose one in Settings > User interface > Display. A change of theme restarts the application. Every shipped theme passes the contrast checks described below, and High Contrast reaches at least 7 to 1 for text.

## The file

A theme is a UTF-8 text file with the extension `.theme`, at most 64 KB and 2000 lines. Blank lines and lines starting with `#` are ignored. Every other line is `name = value`. A name is one of three kinds:

- a header field in lower case: `format`, `name`, `base`, `appearance`, `author`, `description`;
- a constant, starting with `@` and in lower case, such as `@accent`;
- a key in upper case, such as `BORDER`.

A name may be set once per file. Header fields this version does not know are ignored and listed in the summary.

```
format = 1
name = Harbour
base = dark
appearance = dark
author = Your name
description = Blue-grey water with a signal-orange accent.

@page = #10181F
@accent = #FF8A3D
@radius = 4

HIGHLIGHT = #5E4A00
```

### Header

| Field | Meaning |
|---|---|
| `format` | The file format, a whole number. Optional; the current one is 1. A newer number is refused. |
| `name` | The name shown in Settings. Required. Shown as plain text, at most 64 characters. |
| `base` | `light` or `dark`: the theme the missing keys come from. Required in a theme you add, absent in the two built-in ones. |
| `appearance` | `light` or `dark`. Required. It decides which application icon and wordmark are drawn. It may differ from the base. |
| `author`, `description` | Optional, shown in the summary when you add the theme. At most 500 characters each. |

## Constants

A constant is a colour or a whole number that other values refer to, so one change moves everything derived from it. The base defines these; set any of them in your file to replace it:

| Constant | Used for |
|---|---|
| `@page` | The page background and, in the light theme, cards. |
| `@surface` | Toolbars, headers, raised panels. Hover and pressed shades derive from it. |
| `@text` | Body text. |
| `@muted` | Secondary text. |
| `@border` | Hairlines and outlines. |
| `@accent` | The accent: primary buttons, the active tab underline. |
| `@control` | Switched-on controls, selected segments, check marks. |
| `@link` | Links. |
| `@ok` | Success. |
| `@warn` | Warnings and medium severity. |
| `@error` | Errors. |
| `@radius` | The corner radius, a whole number from 0 to 12 in a key. |

You can add constants of your own (`@brand = #112233`) and use them in any value. A constant may refer to another; chains deeper than 64, and cycles, are refused.

## Values

A colour is `#RRGGBB`, or `#RRGGBBAA` with an alpha, a constant, or one of four functions of colours. Percents are written `40%` or `12.5%`, from 0% to 100% with at most one decimal.

| Function | Result |
|---|---|
| `darker(c, p)` | Each of red, green and blue is multiplied by 1 minus p. |
| `lighter(c, p)` | Each channel moves p of the way to 255. |
| `mix(a, b, p)` | p of `a` plus the rest of `b`, channel by channel, alpha included. |
| `alpha(c, p)` | The colour with its alpha set to p of 255. |

Every channel is rounded to the nearest whole number and kept between 0 and 255. Functions nest up to 8 deep. `darker`, `lighter` and `alpha` keep or set the alpha as described; `mix` mixes the alpha of its two colours too.

A number is a whole number, a number constant, or sums and differences of them: `@radius + 2`. A choice is one word from the list of its key.

## Ranges and clamping

| Key | Range or choice |
|---|---|
| `RADIUS` | 0 to 12 |
| `CARD_RADIUS`, `TAB_HOVER_RADIUS` | 0 to 16 |
| `LINE_WIDTH` | 1 to 2 |
| `ICON_STROKE` | 75 to 150, in percent of the normal stroke |
| `TAB_UNDERLINE` | 1 to 4 |
| `TAB_MARKER` | `underline` or `pill` |
| `PRIMARY_BUTTON` | `filled` or `outline` |
| `TABLE_GRID` | `none`, `horizontal` or `both` |
| `TREE_LINES` | `off` or `on` |

A number you write in a key must be in its range, or the theme is refused with the line and the range. A number that the base derives from your constants, such as `@radius + 2`, is moved into the range instead, and the summary lists it as "Values brought into range". No key can change the size of a control: padding, row heights, scrollbar width and divider size are not keys.

## Keys

A key you do not set comes from the base, derived from its constants. Set a key only where a derivation does not give what you want. A key the application does not know is ignored and listed in the summary.

**Surfaces and text**

| Key | Colours |
|---|---|
| `BG` | The page. |
| `SURFACE`, `SURFACE_2`, `SURFACE_3` | Raised surfaces; the hover and the pressed shade of buttons. |
| `CARD`, `HEADER` | Cards and panels; table headers. |
| `BORDER` | Hairlines and outlines. |
| `TEXT`, `MUTED` | Body and secondary text. |
| `LINK` | Link text. |
| `ICON` | Strokes of small drawn icons. |
| `ACCENT_FAINT` | The zebra rows and the out-of-scope banner. |

**Accent, selection and controls**

| Key | Colours |
|---|---|
| `ACCENT`, `ACCENT_DARK` | Primary buttons and the active tab marker; the hover shade. |
| `ACCENT_LIGHT` | Selected rows, a flashing tab. |
| `CONTROL`, `CONTROL_TINT` | Switched-on controls; the tint behind a selected card or list row. |
| `SEGMENT_OFF` | An unselected segment. |
| `TAB_TEXT`, `TAB_TEXT_ON` | Tab titles, unselected and selected. |
| `TOGGLE_OFF` | The track of a switched-off toggle. |
| `SCROLL_THUMB`, `SCROLL_THUMB_HOVER` | The scrollbar thumb. |
| `TEXT_ON_ACCENT`, `TEXT_ON_CONTROL`, `TEXT_ON_SELECTION`, `TEXT_ON_BADGE` | Text and icons on an accent fill, a control fill, a selected row, a round badge. |
| `TEXT_ON_BADGE_DARK` | The dark glyph or number on a badge, pill or coloured row where `TEXT_ON_BADGE` does not read. Each badge uses whichever of the two reads better on its fill. Keep it at or near black, so that one of the two always reaches 4.5 to 1. |

**Status and severity**

| Key | Colours |
|---|---|
| `OK`, `ERROR`, `WARN`, `DIALOG_ERROR` | Success, errors, the dialog warning icon, the dialog error icon. |
| `SEVERITY_HIGH`, `_MEDIUM`, `_LOW`, `_INFO` | Issue severity in tables and badges. |
| `ADVISORY_HIGH`, `_MEDIUM`, `_LOW`, `_INFO` | Issue severity in the advisory text. |

**Messages, comparer and Intruder**

| Key | Colours |
|---|---|
| `CODE_NAME`, `CODE_START`, `CODE_KEY`, `CODE_STRING`, `CODE_NUMBER` | Syntax colouring: header names and tags, the start line, JSON keys, strings, numbers. |
| `HEX_OFFSET`, `HEX_ASCII` | The hex view columns. |
| `HIGHLIGHT` | The search match in message views. |
| `DIFF_DELETED`, `DIFF_ADDED`, `DIFF_MODIFIED` | Comparer backgrounds. |
| `COMPARE_TAKEN` | Text of a Comparer row the other list has taken. |
| `POSITION_MARK` | The fill behind an Intruder payload position. |
| `HIGHLIGHT_RED` ... `HIGHLIGHT_GREY` (nine) | The history highlight swatches. |

**Tools and illustrations**

| Key | Colours |
|---|---|
| `DECODE_URL`, `_HTML`, `_BASE64`, `_HEX`, `_OCTAL`, `_BINARY`, `_COMPRESSED` | The Decoder transforms. |
| `HERO_TOP`, `HERO_BOTTOM`, `HERO_GLOW`, `HERO_TEXT`, `HERO_BODY`, `HERO_MUTED` | The Discover hero band. |
| `INTERCEPT_BEAM`, `_BODY`, `_FACE`, `_LAMP_OFF`, `_LAMP_ON` | The Proxy intercept illustration. |
| `SITE_INSECURE`, `SITE_FOLDER`, `SITE_FOLDER_LIGHT`, `SITE_OVERLAY`, `SITE_PAGE` | Site map icons. |
| `EMPTY_ICON` | The icon of an empty panel. |

**Shape and style**

| Key | Sets |
|---|---|
| `RADIUS`, `CARD_RADIUS`, `TAB_HOVER_RADIUS` | Corner radii of controls, of cards, and of the shape behind a flashing or pill-marked tab. |
| `LINE_WIDTH` | The weight of the outline on fields, cards and outlined buttons. |
| `ICON_STROKE` | The stroke weight of drawn icons, in percent. |
| `TAB_UNDERLINE`, `TAB_MARKER` | The thickness and the style of the selected tab marker. |
| `PRIMARY_BUTTON` | A filled or an outlined primary button. |
| `TABLE_GRID`, `TREE_LINES` | Table grid lines and tree connector lines. |

## Contrast

When you add a theme, OpenTRAP checks the colour pairs that text and marks are drawn with, for example `TEXT` on `BG`, `TEXT` on `CARD`, `LINK` on `BG`, and `TEXT` on `POSITION_MARK`, and every badge, pill and coloured row fill (the accent, the warning and error icons, the severity and Decoder colours) with the better of `TEXT_ON_BADGE` and `TEXT_ON_BADGE_DARK`. Text pairs and badges need 4.5 to 1; secondary text on the page and on a selection, and the severity colours on the page, need 3 to 1. A colour with an alpha is measured as it is painted: over the page, and text over the colour it sits on. The summary lists the pairs your theme changes and that fall short; it does not block the theme. A weakness the base already has is not blamed on you.

## Where themes go and Add from file

Your own themes live in `~/.opentrap/themes`, one file each, and the file name without `.theme` is the theme's id: lower case letters, digits, hyphen and underscore, at most 64 characters. In Settings > User interface > Display choose **Add from file ...**, pick a `.theme` file, and read the summary before confirming:

- the name, the base, the appearance, the author and the description;
- header fields this version ignores;
- how many keys are left to the base;
- keys that were ignored, and values brought into range;
- every low contrast pair;
- whether it replaces a theme you already have with that id.

Adding copies the file into the folder. A file that does not parse, is too large, has no base or names a built-in id is refused with the line and the reason, and nothing is stored. **Remove** deletes a theme of your own; the shipped ones cannot be removed. If the chosen theme cannot be loaded at startup, OpenTRAP falls back to Light or Dark, whichever matches the appearance of the theme you chose (Light if nothing is saved), and says why. If you change `appearance` in the file of the theme in use, the next start does the same and says so; choose the theme again in Settings to use it.
