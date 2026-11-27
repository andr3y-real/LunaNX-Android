# Changelog

## LunaNX Android 3.1-android.23

- Recover more missing video packets during brief latency spikes. Retransmission requests
  use the median of recent RTT probes, and the player adjusts its wait using the response
  times it measures. A larger RTP reorder window helps keep useful packets during bursts.
- Keep video pacing steadier when several decoded frames arrive together. At 60 FPS,
  the player can briefly hold the newest frame for an available display slot, with a
  wait budget of about 4 ms.
- Give the stream a chance to recover before restarting MediaCodec. The player asks for
  a keyframe first and can reuse a keyframe already in the decode queue if a restart is needed.
- The playback report now shows the RTT used for retransmission requests, the latest
  measured response estimate, and how many frames were presented after waiting for a display slot.

In a 90-second simulated RTP replay with intermittent latency spikes and periods of
sustained high latency, expired packet gaps fell from 29 to 15.

## v3.1 — 2026-11-17

- Bug fix update: searching for a game that did not exist made LunaNX crash and close.
  **+ and − no longer quit LunaNX** from the library. The Switch keyboard confirms with +,
  which could still be held when the search results appeared.
  Quit with **Main menu → Quit** or the HOME button. Inputs are also ignored briefly after the
  keyboard closes, so the button that confirmed it cannot trigger another action.
- Search the whole catalog instead of only the current section (Top 10 by default): Luna
  Premium games are included and marked as locked when they are not in the subscription.
- Ask Luna's own search service as well, so games missing from the loaded catalog and titles
  typed with a typo are found. Games found this way are added to All games. If Luna finds
  nothing, its suggestions are not shown as results.
- Ignore case, Latin accents, punctuation, ™/®/© symbols, extra spaces and word order when
  matching titles; the best matches are listed first. A search with no result keeps focus on
  Search (A to edit, B to return).
- Find more games during background catalog exploration: rows that Luna loads separately are
  now resolved, game tiles without artwork are kept, and game pages no longer use up the
  exploration budget.

## v3.0 — 2026-11-17

- Introduce the redesigned cover carousel, new LunaNX logos, branded game launch screen
  and a small version label on the main library only.
- Deliver responsive 720p/60 H.264 streaming with bounded decode queues, latest-frame
  presentation and adaptive image tuning, alongside background artwork/catalog loading.
- Add a single-line performance overlay: battery percentage, estimated battery time,
  clock, measured stream bitrate and presented FPS. Toggle with + and − together.
- Replace the multi-step sign-in screen with one Amazon code flow and automatic confirmation.
  The country picker shows country names only; saved sessions remain supported.
- Order navigation as Main menu, Top 10, Continue playing, All games and Search. Start on
  Top 10, or Continue playing when playable recent history is detected.
- Restore local launch history and current catalog links, hydrate Luna's separate history
  collections across stores/locales, and preserve local history on remote load failures.
- Add touch selection/swipe navigation without accidental launches, retain portrait covers
  received after banners, preserve Amazon image recipes and fit complete cover images.
- Show the active Wi-Fi/Ethernet connection and Luna profile avatar; return to sign-in after logout.
- Separate the Settings scrollbar from text, keep Creator and origin limited to KazaR-RooT,
  and retain offline provenance, release integrity and source comparison tools.
- Validate authentication, history, artwork, input and metrics with host tests. The creator
  confirmed the final release on console and supplied the 3.0 screenshot gallery.

## v2.0.2

Update that takes user feedback and the user experience into account.

**New features**
- Queue position: the waiting screen now shows your position in the Luna queue, and the estimated
  wait when Luna provides it.
- Settings → Check for updates: compares the installed version with the latest LunaNX release
  published on GitHub.

**Fixes**
- Sign out of Amazon now returns to the sign-in screen and forgets the session still in memory.
  The games of the signed-out account can no longer be launched.
- Sign out now deletes all the account data from the SD card, including temporary copies of the
  session and the recently played games. A session renewal in progress can no longer restore the
  account just after signing out.
- Sign out no longer freezes the interface while the console is removed from the Amazon account,
  and the local data is deleted even without a network connection.

## v2.0.1

**New features**
- New countries requested by several users: all 14 Luna countries are now supported. New:
  Poland (amazon.pl), Netherlands (amazon.nl), Sweden (amazon.se), Belgium (amazon.com.be), and
  Austria, Portugal and Luxembourg, which use the amazon.de or amazon.es store while LunaNX tells
  Luna the player's real country. Choose your country at first launch or in Settings → Amazon
  account.
- Sign-in for amazon.pl, .nl, .se and .com.be accounts uses Amazon's European sign-in page on
  amazon.de, shown in the store's language (European Amazon accounts are shared).
- Interface in Polish, Dutch and Swedish (*Polski*, *Nederlands*, *Svenska*): 11 languages in
  total. The country's language is applied automatically when it is chosen.
- The Luna catalog and game texts now also follow the interface language for Dutch, Polish,
  Portuguese and Swedish (Portuguese catalog in European Portuguese, as on the Luna website).

**Known issue**
- Sign in with Amazon (Switch browser) does not work on firmware 23.x (CFW) for now. Use Sign in
  with my phone (QR code), or the `cookies.txt` fallback.

## v2.0

**New features**
- Sign in with your phone: a QR code on the Switch opens a LunaNX page on your phone or tablet
  (same Wi-Fi network). You sign in on the official Amazon page in the phone's browser, then
  paste the address of the last page into the LunaNX page. This sign-in does not use the Switch
  browser, which shows "This feature is not available" on some consoles. Your password is typed
  only on Amazon's site.
- Sign-in in the Switch browser is unchanged and still available.

## v1.2

**Fixes**
- Sign-in outside France: LunaNX always contacted the European Luna server, so United States
  and Canada accounts could get a "service unavailable" error after signing in. Accounts from
  amazon.com and amazon.ca now use the North American Luna server, the other stores the European
  one, with an automatic retry on the other server if the first one refuses.

## v1.1

**New features**
- Interface in 8 languages: Deutsch, English, Español, Français, Italiano, Português, Русский,
  简体中文 (English by default, changeable in Settings → Language). The Luna catalog and game
  texts follow the chosen language when the store offers it.
- Amazon store chosen at first launch (France, Germany, United Kingdom, Spain, Italy, United
  States, Canada); the store's language is applied automatically.
- Complete Luna catalog: live background exploration of the Luna pages ("All games", genres,
  collections) to show games missing from the home rows.
- "All games" grid shown as pages of 10 covers (Previous / Next page buttons, or L / R).
- Automatic return to the library when a game session ends.
- Loading animation (three dots) until the game picture appears.

**Fixes and optimizations**
- Fixed a crash when browsing to the last games: only the current page's covers are kept in
  memory, and oversized artwork is downscaled before display.
- Fixed duplicate games in the library (detection by Amazon ID or by title).
- Fixed a crash when changing the language in Settings.

## v1.0 — Initial release

**Account**
- Amazon sign-in from the console through the official Amazon page, with automatic session
  renewal and sign-out.
- Amazon stores: France, Germany, United Kingdom, Spain, Italy, United States, Canada.

**Library**
- Luna catalog loaded automatically.
- Featured banner, Continue playing (last 10 games), Top 10 and All games (covers loaded 10 at a
  time).
- Optional simple list view.

**Streaming**
- 720p 60 fps H.264 video with software decoding and adaptive image processing.
- Stereo audio.
- Automatic server selection by latency, with manual region choice.
- Bitrate adapted to the network.

**Controls**
- Joy-Con, Pro Controller and handheld mode, with rumble.
- Switch button labels by default; Xbox layout available.
- D-pad can drive the left stick.

**Interface**
- English interface.
