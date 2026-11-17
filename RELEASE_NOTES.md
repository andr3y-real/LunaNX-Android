# LunaNX 3.1 — Bug fix update

LunaNX 3.1 is a small update that fixes a bug: **searching for a game that did not exist made
LunaNX crash and close.** It also improves search while fixing it. Created by **KazaR-RooT**.

## Fixes

- **Searching no longer closes LunaNX.** In 3.0, a search with no result sent you back out of
  LunaNX. **+** and **−** no longer quit from the library: the Switch keyboard confirms with **+**,
  and that button could still be held when the results appeared. To quit, use
  **Main menu → Quit** or the HOME button. Inputs are also ignored briefly after the keyboard
  closes, so the button that confirmed it cannot trigger another action.
- **Search covers every game.** It used to filter only the section on screen (Top 10 by
  default), so most searches found nothing. It now covers the whole catalog, including Luna
  Premium games, which are marked as locked when they are not in your subscription.
- **Missing games are found.** LunaNX also asks Luna's own search service, so games that were
  not loaded yet (for example Alan Wake 2, where available) and titles typed with a typo are
  found. These games are then added to All games.
- **More complete All games.** Background exploration now loads the rows that Luna sends
  separately, keeps game tiles without artwork and no longer spends its budget on game pages.

## Search tips

- Case, Latin accents, punctuation, ™/®/© symbols, extra spaces and word order are ignored:
  `pokemon cafe`, `wake alan` and `alanwake` all work. The best matches are listed first.
- If nothing matches, focus stays on **Search**: press **A** to correct the text, or **B** to
  return to the library. Choosing Top 10, Continue playing or All games also closes the search.

## Install or update

1. Download **LunaNX.nro** and copy it to **sdmc:/switch/LunaNX/LunaNX.nro**, replacing 3.0.
2. Launch through the Homebrew Menu in **application mode**: hold **R** while opening a game.

Saved sessions and settings are kept when replacing the NRO. Keep one copy of LunaNX.nro on
the SD card. To leave a game, press **ZL + ZR + −** together. During gameplay, **+ and −**
together still toggle the performance line.

## Validation and integrity

Offline host tests cover title matching (accents, symbols, word order, spacing, empty input and
truncated UTF-8), input shortcuts, metrics, artwork and provenance. The Switch build has no
compiler warnings in LunaNX sources, and the source/NRO integrity check passes. Luna's search
service and catalog are only available with a signed-in account, so search results and the
number of games found depend on your country and subscription.

SHA-256 of **LunaNX.nro**:

```text
b7991a2c7c6aadf719d797c75e8a98dc24b9eb9474daaa7131a3d9c1595ed215
```

**Downloads:** LunaNX.nro, SHA256SUMS.txt, LunaNX-v3.1-Licenses.zip and the original LunaNX-V3.png logo.
The exact corresponding source is available from this **v3.1** tag and GitHub's source archives.
Changes since 3.0 are listed in [CHANGELOG.md](https://github.com/kazar-root/LunaNX/blob/v3.1/CHANGELOG.md).

**Creator:** KazaR-RooT · **License:** GPL-3.0 ·
[Third-party credits](https://github.com/kazar-root/LunaNX/blob/v3.1/THIRD_PARTY_NOTICES.md)
