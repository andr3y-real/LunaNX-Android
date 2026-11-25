package org.lunanx.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.animation.ValueAnimator;
import android.graphics.drawable.RippleDrawable;
import android.view.animation.DecelerateInterpolator;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.content.pm.ActivityInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import android.hardware.input.InputManager;
import android.view.GestureDetector;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Base64;
import android.util.LruCache;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.AbsListView;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ViewFlipper;
import android.view.animation.TranslateAnimation;
import android.view.animation.Animation;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Enumeration;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

public final class MainActivity extends Activity {
    static { System.loadLibrary("lunanx"); }

    private static final int BACKGROUND = 0xff10121c;
    private static final int CARD = 0xff1c2030;
    private static final int ACCENT = 0xffae9aff;
    private static final int MUTED = 0xffbac0d4;
    // Row layout from game_rows in the JNI bridge.
    private static final int GAME_FIELDS = 8;
    private static final String LIST_LAYOUT_PREF = "library_list";
    private static final int COVER = 1;
    private static final int OFFER = 2;
    private static final int BANNER_FALLBACK = 3;
    private static final int PREMIUM = 4;
    private static final int GAME_KEY = 5;
    private static final int DETAILS = 6;
    private static final int GENRES = 7;
    private static final int ALL_GAMES = 0;
    private static final int TOP_GAMES = 1;
    private static final int RECENT_GAMES = 2;
    private static final int PREMIUM_GAMES = 3;
    private static final String[] SECTIONS = {"Todos", "Top 10", "Continuar", "Premium"};
    private static final int STREAMING = 3;
    private static final int ENDED = 4;

    private static final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private static final ExecutorService catalogWorker = Executors.newSingleThreadExecutor();
    private static final ExecutorService artwork = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> covers = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ConnectivityManager connectivity;
    private volatile long operation;
    private volatile boolean destroyed;
    private boolean authenticating;
    private boolean loadingGames;
    private FrameLayout player;
    private GridView grid;
    private int libraryDirection;
    private int selection;
    private boolean listLayout;
    private boolean libraryVisible;
    private boolean home;
    private boolean settingsScreen;
    private boolean inlineSearch;
    private boolean focusSearch;
    private int catalogSort;
    private int[] visiblePositions = new int[0];
    private String homeGenre = "";
    private int homeScrollPosition;
    private String[][] homeRows;
    private ScrollView homeScroll;
    private static final int LIBRARY_BACKGROUND = 0xff111019;
    private static final int LIBRARY_CARD = 0xff222031;
    private static final int LIBRARY_SELECTED = 0xffd0baff;
    private String[] libraryInfo = {"", "", "", "", "", "0"};
    private volatile long catalogGeneration;
    private volatile boolean catalogPaused;
    private boolean catalogRefreshPending;
    private TextView libraryStatus;
    private TextView streamStatus;
    private TextView metrics;
    private View controls;
    private View playerMenuButton;
    private SurfaceView playerSurface;
    private AlertDialog playerMenu;
    private PlayerFraming playerFraming;
    private InputManager inputManager;
    private boolean gamepadConnected;
    private final InputManager.InputDeviceListener inputListener = new InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id) { refreshGamepads(); }
        @Override public void onInputDeviceRemoved(int id) { refreshGamepads(); }
        @Override public void onInputDeviceChanged(int id) { refreshGamepads(); }
    };
    private String[] markets = new String[0];
    private String[] games;
    private String search = "";
    private int section;
    private boolean showMetrics;
    private boolean touchControls = true;
    private ScheduledFuture<?> playbackTicker;
    private PlaybackReport playbackReport;
    private String playerTitle;
    private int padButtons;
    private int hatButtons;
    private int controllerDevice = -1;
    private int touchButtons;
    private float lx, ly, rx, ry, leftTrigger, rightTrigger;
    private float touchLx, touchLy, touchRx, touchRy;
    private volatile Pad pad = new Pad(0, 0, 0, 0, 0, 0, 0);

    private static final class Pad {
        final int buttons;
        final float lt, rt, x, y, z, rz;
        Pad(int buttons, float lt, float rt, float x, float y, float z, float rz) {
            this.buttons = buttons;
            this.lt = lt;
            this.rt = rt;
            this.x = x;
            this.y = y;
            this.z = z;
            this.rz = rz;
        }
    }

    private native void nativeInit(String path);
    private native String[] nativeMarkets();
    private native String[] nativeLoad();
    private native String[] nativeSignIn(String market, long token);
    private native void nativeSignOut();
    private native String[] nativeSearch(String query);
    private native String[] nativeSection(int section);
    private native String[] nativeLibraryInfo();
    private native boolean nativeExplore(long generation, boolean force);
    private native void nativeStart(int index, Surface surface, boolean preferBrazilianPortuguese);
    private native int nativeTick(int buttons, float lt, float rt, float lx, float ly, float rx, float ry);
    private native String[] nativeStatus();
    private native boolean nativeSetVideoSurface(Surface surface);
    private native void nativeStop();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        inputManager = getSystemService(InputManager.class);
        connectivity = getSystemService(ConnectivityManager.class);
        if (inputManager != null) inputManager.registerInputDeviceListener(inputListener, ui);
        showLoading("Carregando a sessão Amazon…");
        long token = ++operation;
        worker.execute(() -> {
            try {
                prepareTrustStore();
                nativeInit(getFilesDir().getAbsolutePath());
                markets = nativeMarkets();
                String[] library = nativeLoad();
                if (library == null) post(token, () -> showSignIn(null));
                else openLibrary(token);
            } catch (Exception error) {
                post(token, () -> showSignIn(message(error)));
            }
        });
    }

    private void prepareTrustStore() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidCAStore");
        store.load(null);
        File bundle = new File(getFilesDir(), "android-ca.pem");
        File temporary = new File(getFilesDir(), "android-ca.tmp");
        int written = 0;
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            Enumeration<String> aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (!alias.startsWith("system:")) continue;
                Certificate certificate = store.getCertificate(alias);
                if (certificate == null) continue;
                String encoded = Base64.encodeToString(certificate.getEncoded(), Base64.NO_WRAP);
                String pem = "-----BEGIN CERTIFICATE-----\n" + encoded + "\n-----END CERTIFICATE-----\n";
                out.write(pem.getBytes(StandardCharsets.US_ASCII));
                written++;
            }
        }
        if (written == 0 || !temporary.renameTo(bundle))
            throw new IllegalStateException("Não foi possível preparar os certificados do Android.");
    }

    private boolean isCancelled(long token) { return destroyed || operation != token; }

    private void post(long token, Runnable task) {
        ui.post(() -> { if (!isCancelled(token)) task.run(); });
    }

    private String message(Exception error) {
        String message = error.getLocalizedMessage();
        return message == null || message.isEmpty() ? "A operação falhou." : message;
    }

    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private TextView heading(String value) {
        TextView title = text(value, 28, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return title;
    }

    private Button button(String label, Runnable action) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setOnClickListener(ignored -> action.run());
        return view;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private LinearLayout screen(boolean center) {
        LinearLayout content = screenContent();
        if (center) content.setGravity(Gravity.CENTER);
        setContentView(content);
        return content;
    }

    private LinearLayout screenContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(16), dp(18), dp(16));
        content.setBackgroundColor(BACKGROUND);
        return content;
    }

    private void showLoading(String status) {
        LinearLayout content = screen(true);
        content.addView(new ProgressBar(this), new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView label = text(status, 16, MUTED);
        label.setPadding(0, dp(12), 0, 0);
        content.addView(label);
    }

    private void showSignIn(String error) {
        authenticating = false;
        LinearLayout content = screen(true);
        content.addView(heading("LunaNX"));
        TextView subtitle = text("Entre com sua conta Amazon Luna", 17, MUTED);
        subtitle.setPadding(0, dp(8), 0, dp(14));
        content.addView(subtitle);
        if (error != null && !error.isEmpty()) {
            TextView failure = text(error, 14, 0xffffaab7);
            failure.setGravity(Gravity.CENTER);
            failure.setPadding(dp(8), 0, dp(8), dp(10));
            content.addView(failure);
        }

        if (markets.length == 0) {
            content.addView(button("Tentar novamente", this::recreate));
            return;
        }
        String[] names = new String[markets.length / 2];
        String saved = getPreferences(MODE_PRIVATE).getString("market", "pt");
        int selected = 0;
        for (int i = 0; i < names.length; i++) {
            names[i] = markets[i * 2 + 1];
            if (markets[i * 2].equals(saved)) selected = i;
        }
        Spinner market = new Spinner(this);
        market.setContentDescription("País da conta Amazon Luna");
        ArrayAdapter<String> choices = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        market.setAdapter(choices);
        market.setSelection(selected);
        content.addView(market, new LinearLayout.LayoutParams(dp(250), dp(48)));
        content.addView(button("Gerar código Amazon", () -> signIn(markets[market.getSelectedItemPosition() * 2])));
        if (error != null) content.addView(button("Recarregar sessão salva", this::recreate));
    }

    private void signIn(String market) {
        getPreferences(MODE_PRIVATE).edit().putString("market", market).apply();
        authenticating = true;
        long token = ++operation;
        LinearLayout content = screen(true);
        content.addView(new ProgressBar(this));
        content.addView(text("Solicitando código à Amazon…", 16, MUTED));
        content.addView(button("Cancelar", this::cancelSignIn));
        worker.execute(() -> {
            try {
                String[] library = nativeSignIn(market, token);
                if (library != null) openLibrary(token);
            } catch (Exception error) {
                post(token, () -> showSignIn(message(error)));
            }
        });
    }

    private void cancelSignIn() {
        ++operation;
        showSignIn(null);
    }

    private void onChallenge(long token, String code, String url, byte[] modules) {
        post(token, () -> {
            LinearLayout content = screen(true);
            content.addView(heading("Código Amazon"));
            TextView instructions = text("Abra este endereço em outro aparelho ou escaneie o QR.", 15, MUTED);
            instructions.setGravity(Gravity.CENTER);
            content.addView(instructions);
            TextView address = text(url, 15, ACCENT);
            address.setTextIsSelectable(true);
            address.setGravity(Gravity.CENTER);
            address.setPadding(0, dp(8), 0, dp(8));
            content.addView(address);
            if (modules != null && modules.length > 0) {
                int side = (int) Math.sqrt(modules.length);
                int quiet = 4;
                Bitmap qr = Bitmap.createBitmap(side + 2 * quiet, side + 2 * quiet, Bitmap.Config.ARGB_8888);
                qr.eraseColor(Color.WHITE);
                for (int y = 0; y < side; y++)
                    for (int x = 0; x < side; x++)
                        if (modules[y * side + x] != 0) qr.setPixel(x + quiet, y + quiet, Color.BLACK);
                ImageView image = new ImageView(this);
                image.setImageBitmap(Bitmap.createScaledBitmap(qr, dp(184), dp(184), false));
                image.setContentDescription("QR de autenticação da Amazon");
                content.addView(image, new LinearLayout.LayoutParams(dp(184), dp(184)));
            }
            TextView userCode = heading(code);
            userCode.setTextIsSelectable(true);
            userCode.setPadding(0, dp(10), 0, dp(10));
            content.addView(userCode);
            content.addView(text("Aguardando confirmação da Amazon…", 15, MUTED));
            content.addView(button("Cancelar", this::cancelSignIn));
        });
    }

    private void openLibrary(long token) {
        String[] info = nativeLibraryInfo();
        String[][] rows = readHomeRows();
        post(token, () -> {
            authenticating = false;
            libraryInfo = info;
            homeGenre = "";
            homeScrollPosition = 0;
            showHome(rows);
            startCatalogDiscovery(false);
        });
    }

    // Read Todos last so the native result table matches the default library section.
    private String[][] readHomeRows() {
        String[] top = nativeSection(TOP_GAMES);
        String[] recent = nativeSection(RECENT_GAMES);
        return new String[][]{nativeSection(ALL_GAMES), top, recent};
    }

    private void openHome() {
        if (loadingGames) return;
        loadingGames = true;
        long token = ++operation;
        worker.execute(() -> {
            try {
                String[][] rows = readHomeRows();
                post(token, () -> {
                    homeGenre = "";
                    homeScrollPosition = 0;
                    showHome(rows);
                });
            } catch (Exception error) {
                post(token, () -> {
                    loadingGames = false;
                    libraryStatus.setText(message(error));
                });
            }
        });
    }

    private void showHome(String[][] rows) {
        showHome(rows, false);
    }

    private void showHome(String[][] rows, boolean preserveScroll) {
        if (preserveScroll && homeScroll != null) homeScrollPosition = homeScroll.getScrollY();
        home = true;
        settingsScreen = false;
        inlineSearch = false;
        grid = null;
        homeRows = rows;
        games = rows[ALL_GAMES];
        section = ALL_GAMES;
        search = "";
        selection = 0;
        loadingGames = false;
        libraryVisible = true;
        listLayout = getPreferences(MODE_PRIVATE).getBoolean(LIST_LAYOUT_PREF, false);
        LinearLayout page = libraryPage();
        homeScroll = null;
        libraryStatus = text("", 11, MUTED);
        page.addView(libraryStatus);
        if (homeGenre.isEmpty()) {
            homeScroll = new ScrollView(this);
            homeScroll.setVerticalScrollBarEnabled(false);
            page.addView(homeScroll, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(16), dp(4), dp(16), dp(16));
            homeScroll.addView(body, new ScrollView.LayoutParams(-1, -2));
            sectionHeading(body, "Biblioteca", gameCount(rows[ALL_GAMES]), () -> loadSection(ALL_GAMES));
            addCatalogPreview(body, rows[ALL_GAMES]);
            addSpotlight(body, rows[TOP_GAMES]);

            if (rows[RECENT_GAMES].length != 0) {
                sectionHeading(body, "Continuar a jogar", "", () -> loadSection(RECENT_GAMES));
                addRecentCovers(body, rows[RECENT_GAMES]);
            }
            sectionHeading(body, "Categorias", "", null);
            addCategories(body);
        } else {
            addGenreList(page);
        }
        addBottomBar(page, true);
        ScrollView scroll = homeScroll;
        if (scroll != null) {
            scroll.post(() -> scroll.scrollTo(0, homeScrollPosition));
            if (!preserveScroll && homeScrollPosition == 0) enterLibraryView(scroll, 0, 12);
        }
    }

    private void addGenreList(LinearLayout page) {
        String[] filtered = gamesInGenre(homeGenre);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(4), dp(16), dp(10));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(heading(homeGenre));
        labels.addView(text(gameCount(filtered), 14, MUTED));
        header.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        View back = libraryAction("‹  Início", false, this::openHome);
        header.addView(back);
        page.addView(header);
        GridView categories = new GridView(this);
        configurePosterGrid(categories, libraryColumnsForWidth());
        categories.setAdapter(new GameAdapter(filtered, false));
        categories.setOnItemClickListener((parent, view, position, id) ->
            launchFromHome(ALL_GAMES, filtered[position * GAME_FIELDS + GAME_KEY]));
        if (filtered.length == 0) libraryStatus.setText("Nenhum jogo nesta categoria");
        page.addView(categories, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private String gameCount(String[] rows) {
        int count = rows.length / GAME_FIELDS;
        return count + (count == 1 ? " jogo" : " jogos");
    }

    private LinearLayout libraryPage() {
        LinearLayout page = screenContent();
        page.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xff241a36, LIBRARY_BACKGROUND, 0xff0c0b12}));
        getWindow().setStatusBarColor(LIBRARY_BACKGROUND);
        getWindow().setNavigationBarColor(LIBRARY_BACKGROUND);
        page.setPadding(0, dp(4), 0, 0);
        setContentView(page);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), 0, dp(5), 0);
        header.setBackground(chrome(0xbb15111e, 24));
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.lunanx_icon);
        mark.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mark.setBackground(rounded(LIBRARY_CARD, 10));
        mark.setClipToOutline(true);
        header.addView(mark, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView title = heading("LunaNX");
        title.setPadding(dp(10), 0, 0, 0);
        title.setTextSize(22);
        header.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setOnLongClickListener(v -> { showLibraryMenu(); return true; });
        View filters = icon("filter", "Ordenar e filtrar", this::showLibraryFilters);
        header.addView(filters, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams headerSize = new LinearLayout.LayoutParams(-1, dp(56));
        headerSize.setMargins(dp(10), 0, dp(10), dp(8));
        page.addView(header, headerSize);
        return page;
    }

    private void showLibrarySearch() {
        if (loadingGames) return;
        inlineSearch = true;
        focusSearch = true;
        if (home || settingsScreen) loadSection(ALL_GAMES);
        else showLibrary(games);
    }

    private void addInlineSearch(LinearLayout page) {
        LinearLayout field = new LinearLayout(this);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(dp(6), 0, dp(6), 0);
        field.setBackground(chrome(0xff241c34, 28));
        EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setHint("Pesquisar jogos");
        query.setText(search);
        query.setTextSize(16);
        query.setBackgroundColor(Color.TRANSPARENT);
        Runnable submit = () -> {
            String term = query.getText().toString().trim();
            InputMethodManager keyboard = getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(query.getWindowToken(), 0);
            query.clearFocus();
            if (term.isEmpty()) loadSection(ALL_GAMES);
            else loadGames(term);
        };
        field.addView(query, new LinearLayout.LayoutParams(0, -2, 1));
        field.addView(icon("search", "Buscar jogos", submit), new LinearLayout.LayoutParams(dp(48), dp(48)));
        query.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            submit.run();
            return true;
        });
        LinearLayout.LayoutParams fieldSize = new LinearLayout.LayoutParams(-1, -2);
        fieldSize.setMargins(dp(16), dp(2), dp(16), dp(6));
        page.addView(field, fieldSize);
        if (focusSearch) {
            focusSearch = false;
            query.requestFocus();
            query.setSelection(query.length());
            query.post(() -> {
                InputMethodManager keyboard = getSystemService(InputMethodManager.class);
                if (keyboard != null && query.hasFocus())
                    keyboard.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT);
            });
        }
    }

    private void addBottomBar(LinearLayout page, boolean atHome) {
        LinearLayout bottom = new LinearLayout(this);
        bottom.setPadding(dp(4), dp(5), dp(4), dp(8));
        bottom.setBackgroundColor(0xff171222);
        bottom.addView(navButton("home", "Início", atHome && !settingsScreen, this::openHome),
            new LinearLayout.LayoutParams(0, -2, 1));
        bottom.addView(navButton("search", "Pesquisar", false, this::showLibrarySearch),
            new LinearLayout.LayoutParams(0, -2, 1));
        bottom.addView(navButton("grid", "Biblioteca", !atHome && !settingsScreen, () -> {
            if (loadingGames) return;
            inlineSearch = false;
            loadSection(ALL_GAMES);
        }), new LinearLayout.LayoutParams(0, -2, 1));
        bottom.addView(navButton("settings", "Ajustes", settingsScreen, this::showSettings),
            new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
    }

    private View navButton(String symbol, String label, boolean selected, Runnable action) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(tapBackground(Color.TRANSPARENT, 18));
        int color = selected ? 0xfff3e9ff : MUTED;
        tile.setMinimumHeight(dp(56));
        tile.setPadding(dp(2), dp(6), dp(2), dp(6));
        FrameLayout marker = new FrameLayout(this);
        if (selected) marker.setBackground(rounded(0xff51376f, 15));
        View glyph = libraryGlyph(symbol, color);
        marker.addView(glyph, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        tile.addView(marker, new LinearLayout.LayoutParams(dp(60), dp(34)));
        if (selected && ValueAnimator.areAnimatorsEnabled()) {
            glyph.setScaleX(0.92f);
            glyph.setScaleY(0.92f);
            glyph.animate().scaleX(1.12f).scaleY(1.12f).setDuration(190)
                .setInterpolator(new DecelerateInterpolator()).start();
        }
        TextView caption = text(label, 12, color);
        caption.setMaxLines(2);
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(0, dp(4), 0, 0);
        if (selected) caption.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tile.addView(caption);
        tile.setContentDescription(label);
        tile.setSelected(selected);
        tile.setFocusable(true);
        tile.setOnClickListener(v -> action.run());
        return tile;
    }

    private View icon(String name, String label, Runnable action) {
        FrameLayout view = new FrameLayout(this);
        view.setBackground(tapBackground(0xff30223e, 17));
        view.addView(libraryGlyph(name, Color.WHITE),
            new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        view.setContentDescription(label);
        view.setFocusable(true);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    // Android vector icons from the MIT-licensed OpenNOW Android source.
    private View libraryGlyph(String name, int tint) {
        int resource;
        switch (name) {
            case "home": resource = R.drawable.opennow_ic_tab_store; break;
            case "grid": resource = R.drawable.opennow_ic_tab_library; break;
            case "search": resource = R.drawable.opennow_ic_search; break;
            case "settings": resource = R.drawable.opennow_ic_tab_settings; break;
            case "filter": resource = R.drawable.opennow_ic_sort_filter; break;
            default: return new LibraryIcon(name, tint);
        }
        ImageView icon = new ImageView(this);
        icon.setImageResource(resource);
        icon.setImageTintList(ColorStateList.valueOf(tint));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    private GradientDrawable chrome(int fill, int radius) {
        GradientDrawable shape = rounded(fill, radius);
        shape.setStroke(dp(1), 0x337e659b);
        return shape;
    }

    private RippleDrawable tapBackground(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x55c5a7ff), rounded(color, radius),
            rounded(Color.WHITE, radius));
    }

    private void enterLibraryView(View view, int delay, int slideDp) {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        view.setAlpha(0f);
        view.setTranslationY(dp(slideDp));
        view.animate().alpha(1f).translationY(0f).setStartDelay(delay)
            .setDuration(240).setInterpolator(new DecelerateInterpolator()).start();
    }

    private TextView libraryAction(String label, boolean primary, Runnable action) {
        TextView view = text(label, 15, primary ? LIBRARY_BACKGROUND : Color.WHITE);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(48));
        view.setPadding(dp(16), dp(7), dp(16), dp(7));
        view.setBackground(tapBackground(primary ? LIBRARY_SELECTED : 0xff332743, 22));
        view.setFocusable(true);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private final class LibraryIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final String name;

        LibraryIcon(String name, int color) {
            super(MainActivity.this);
            this.name = name;
            paint.setColor(color);
            paint.setStrokeWidth(1.7f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.save();
            canvas.scale(getWidth() / 24f, getHeight() / 24f);
            switch (name) {
                case "grid":
                    for (int y = 4; y <= 13; y += 9)
                        for (int x = 4; x <= 13; x += 9)
                            canvas.drawRoundRect(x, y, x + 7, y + 7, 1.5f, 1.5f, paint);
                    break;
                case "trophy":
                    drawPath(canvas, 7, 4, 17, 4, 17, 11, 15, 14, 9, 14, 7, 11, 7, 4);
                    drawPath(canvas, 7, 6, 3, 6, 3, 9, 5, 11, 7, 11);
                    drawPath(canvas, 17, 6, 21, 6, 21, 9, 19, 11, 17, 11);
                    drawPath(canvas, 12, 14, 12, 20);
                    drawPath(canvas, 7, 20, 17, 20);
                    break;
                case "play": drawPath(canvas, 7, 4, 20, 12, 7, 20, 7, 4); break;
                case "action":
                    drawPath(canvas, 7, 7, 17, 7, 20, 10, 22, 17, 19, 19, 15, 15,
                        9, 15, 5, 19, 2, 17, 4, 10, 7, 7);
                    drawPath(canvas, 6, 11, 10, 11);
                    drawPath(canvas, 8, 9, 8, 13);
                    canvas.drawPoint(16, 10, paint);
                    canvas.drawPoint(18, 12, paint);
                    break;
                case "adventure":
                    canvas.drawCircle(12, 12, 9, paint);
                    drawPath(canvas, 8, 16, 10, 10, 16, 8, 14, 14, 8, 16);
                    break;
                case "simulation":
                    canvas.drawCircle(9, 7, 3, paint);
                    canvas.drawCircle(17, 9, 2.5f, paint);
                    canvas.drawArc(2, 13, 16, 23, 180, 180, false, paint);
                    canvas.drawArc(14, 13, 23, 23, 215, 145, false, paint);
                    break;
                case "indie":
                    drawPath(canvas, 3, 9, 12, 4, 21, 9, 12, 14, 3, 9);
                    drawPath(canvas, 3, 13, 12, 18, 21, 13);
                    drawPath(canvas, 3, 17, 12, 22, 21, 17);
                    break;
                default:
                    canvas.drawCircle(12, 8, 3.5f, paint);
                    canvas.drawArc(4, 13, 20, 25, 180, 180, false, paint);
                    break;
            }
            canvas.restore();
        }

        private void drawPath(Canvas canvas, float... points) {
            path.rewind();
            path.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
            canvas.drawPath(path, paint);
        }
    }

    private void sectionHeading(LinearLayout body, String title, String detail, Runnable action) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text(title, 20, Color.WHITE);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(label);
        if (!detail.isEmpty()) labels.addView(text(detail, 13, MUTED));
        line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            TextView more = text("Ver todos  ›", 13, Color.WHITE);
            more.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            more.setPadding(dp(12), 0, dp(2), 0);
            more.setFocusable(true);
            more.setOnClickListener(v -> action.run());
            more.setMinHeight(dp(48));
            line.addView(more, new LinearLayout.LayoutParams(-2, -2));
        }
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        line.setMinimumHeight(dp(48));
        size.topMargin = dp(10);
        body.addView(line, size);
    }

    private LinearLayout rail(LinearLayout body, int height) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout items = new LinearLayout(this);
        scroll.addView(items, new HorizontalScrollView.LayoutParams(-2, -1));
        body.addView(scroll, new LinearLayout.LayoutParams(-1, dp(height)));
        return items;
    }

    private ImageView coverView(String url, int radius) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(rounded(LIBRARY_CARD, radius));
        image.setClipToOutline(true);
        bindCover(image, url);
        return image;
    }

    private void addRecentCovers(LinearLayout body, String[] rows) {
        int width = Math.min(96, Math.max(76, getResources().getConfiguration().screenWidthDp / 4));
        LinearLayout items = rail(body, Math.round(width * 888f / 628f));
        for (int offset = 0; offset < Math.min(rows.length, 8 * GAME_FIELDS); offset += GAME_FIELDS) {
            String key = rows[offset + GAME_KEY];
            PosterCard tile = new PosterCard();
            tile.bind(rows, offset);
            tile.setFocusable(true);
            tile.setOnClickListener(v -> launchFromHome(RECENT_GAMES, key));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(width), -1);
            size.rightMargin = dp(10);
            items.addView(tile, size);
        }
    }

    private void addCatalogPreview(LinearLayout body, String[] rows) {
        int columns = libraryColumnsForWidth();
        int count = Math.min(rows.length / GAME_FIELDS, columns * 2);
        if (count == 0) {
            body.addView(text("Nenhum jogo encontrado", 14, MUTED));
            return;
        }
        int width = getResources().getConfiguration().screenWidthDp - 32;
        int height = Math.round(Math.max(1, width - 12 * (columns - 1)) * 888f / (628f * columns));
        for (int first = 0; first < count; first += columns) {
            LinearLayout line = new LinearLayout(this);
            LinearLayout.LayoutParams lineSize = new LinearLayout.LayoutParams(-1, dp(height));
            lineSize.bottomMargin = dp(16);
            body.addView(line, lineSize);
            for (int column = 0; column < columns; ++column) {
                int index = first + column;
                View tile = new View(this);
                if (index < count) {
                    int offset = index * GAME_FIELDS;
                    PosterCard cover = new PosterCard();
                    cover.bind(rows, offset);
                    cover.setFocusable(true);
                    cover.setOnClickListener(v -> launchFromHome(ALL_GAMES, rows[offset + GAME_KEY]));
                    tile = cover;
                }
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -1, 1);
                if (column != 0) size.leftMargin = dp(12);
                line.addView(tile, size);
            }
        }
    }

    private void addSpotlight(LinearLayout body, String[] top) {
        if (top.length == 0) return;
        sectionHeading(body, "Em destaque", "", () -> loadSection(TOP_GAMES));
        ViewFlipper spotlight = new ViewFlipper(this) {
            @Override protected void onWindowVisibilityChanged(int visibility) {
                super.onWindowVisibilityChanged(visibility);
                updateSpotlightPlayback();
            }

            @Override public void onWindowFocusChanged(boolean hasFocus) {
                super.onWindowFocusChanged(hasFocus);
                updateSpotlightPlayback();
            }

            private void updateSpotlightPlayback() {
                android.view.accessibility.AccessibilityManager accessibility =
                    getSystemService(android.view.accessibility.AccessibilityManager.class);
                if (isAttachedToWindow() && hasWindowFocus() && getWindowVisibility() == View.VISIBLE && getChildCount() > 1 &&
                    !hasFocus() && ValueAnimator.areAnimatorsEnabled() &&
                    (accessibility == null || !accessibility.isTouchExplorationEnabled())) startFlipping();
                else stopFlipping();
            }

            @Override public void requestChildFocus(View child, View focused) {
                super.requestChildFocus(child, focused);
                updateSpotlightPlayback();
            }

            @Override public void clearChildFocus(View child) {
                super.clearChildFocus(child);
                post(this::updateSpotlightPlayback);
            }
        };
        spotlight.setFlipInterval(5500);
        spotlight.setAnimateFirstView(false);
        spotlight.setInAnimation(spotlightSlide(1, 0));
        spotlight.setOutAnimation(spotlightSlide(0, -1));
        spotlight.setBackground(chrome(LIBRARY_CARD, 18));
        spotlight.setClipToOutline(true);

        for (int i = 0; i < Math.min(5, top.length / GAME_FIELDS); ++i) {
            int offset = i * GAME_FIELDS;
            LinearLayout card = new LinearLayout(this);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(10), dp(10), dp(12), dp(10));
            card.setForeground(tapBackground(Color.TRANSPARENT, 18));
            card.setFocusable(true);
            card.setContentDescription("Jogar " + top[offset]);
            card.setOnClickListener(v -> launchFromHome(TOP_GAMES, top[offset + GAME_KEY]));
            PosterImage poster = new PosterImage();
            bindPoster(poster, top, offset);
            card.addView(poster, new LinearLayout.LayoutParams(dp(58), dp(80)));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(12), 0, dp(8), 0);
            TextView title = text(top[offset], 18, Color.WHITE);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(title);
            if (!top[offset + DETAILS].isEmpty()) {
                TextView details = text(top[offset + DETAILS], 13, MUTED);
                details.setMaxLines(2);
                details.setEllipsize(android.text.TextUtils.TruncateAt.END);
                labels.addView(details);
            }
            card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            TextView play = text("▶", 17, LIBRARY_SELECTED);
            play.setGravity(Gravity.CENTER);
            play.setBackground(rounded(0xff3d2c54, 16));
            card.addView(play, new LinearLayout.LayoutParams(dp(40), dp(40)));
            spotlight.addView(card, new FrameLayout.LayoutParams(-1, -2));
        }
        body.addView(spotlight, new LinearLayout.LayoutParams(-1, -2));
    }

    private Animation spotlightSlide(float from, float to) {
        TranslateAnimation animation = new TranslateAnimation(Animation.RELATIVE_TO_SELF, from,
            Animation.RELATIVE_TO_SELF, to, Animation.ABSOLUTE, 0, Animation.ABSOLUTE, 0);
        animation.setDuration(320);
        animation.setInterpolator(new DecelerateInterpolator());
        return animation;
    }

    private void addCategories(LinearLayout body) {
        LinearLayout items = null;
        int columns = getResources().getConfiguration().screenWidthDp >= 520 ? 3 : 2;
        String[] names = {"Ação", "Aventura", "Simulação", "Indie", "Mais"};
        String[] symbols = {"action", "adventure", "simulation", "indie", "grid"};
        for (int i = 0; i < names.length; ++i) {
            if (i % columns == 0) {
                items = new LinearLayout(this);
                LinearLayout.LayoutParams rowSize = new LinearLayout.LayoutParams(-1, -2);
                rowSize.bottomMargin = dp(8);
                body.addView(items, rowSize);
            }
            String name = names[i];
            LinearLayout chip = new LinearLayout(this);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(6), dp(6), dp(6), dp(6));
            chip.setMinimumHeight(dp(48));
            chip.addView(new LibraryIcon(symbols[i], Color.WHITE),
                new LinearLayout.LayoutParams(dp(14), dp(14)));
            TextView label = text(name, 14, Color.WHITE);
            label.setPadding(dp(5), 0, 0, 0);
            chip.addView(label);
            chip.setContentDescription(name);
            chip.setBackground(tapBackground(LIBRARY_CARD, 13));
            chip.setFocusable(true);
            chip.setOnClickListener(v -> {
                if (name.equals("Mais")) showCategories();
                else openCategory(name);
            });
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
            if (i % columns != 0) size.leftMargin = dp(8);
            items.addView(chip, size);
        }
    }

    private void showCategories() {
        if (loadingGames) return;
        java.util.Set<String> genres = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (int offset = GENRES; offset < games.length; offset += GAME_FIELDS)
            for (String genre : games[offset].split("\u001f"))
                if (!genre.isEmpty()) genres.add(genreLabel(genre));
        Dialog dialog = new Dialog(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(chrome(0xff20172d, 26));
        scroll.setClipToOutline(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(16));
        scroll.addView(panel);
        TextView title = heading("Categorias");
        title.setPadding(0, 0, 0, dp(12));
        panel.addView(title);
        LinearLayout row = null;
        int i = 0;
        for (String genre : genres) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
                size.bottomMargin = dp(8);
                panel.addView(row, size);
            }
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
            if (i++ % 2 == 1) size.leftMargin = dp(8);
            row.addView(libraryAction(genre, false, () -> {
                dialog.dismiss();
                openCategory(genre);
            }), size);
        }
        panel.addView(libraryAction("Luna Premium", true, () -> {
            dialog.dismiss();
            loadSection(PREMIUM_GAMES);
        }));
        showLibraryDialog(dialog, scroll);
    }

    private void openCategory(String genre) {
        if (loadingGames) return;
        homeGenre = genre;
        homeScrollPosition = 0;
        showHome(homeRows);
    }

    private String[] gamesInGenre(String genre) {
        java.util.List<String> filtered = new java.util.ArrayList<>();
        for (int offset = 0; offset < games.length; offset += GAME_FIELDS) {
            for (String tag : games[offset + GENRES].split("\u001f")) {
                if (genreLabel(tag).equalsIgnoreCase(genre)) {
                    for (int i = 0; i < GAME_FIELDS; ++i) filtered.add(games[offset + i]);
                    break;
                }
            }
        }
        return filtered.toArray(new String[0]);
    }

    private static String genreLabel(String genre) {
        String name = java.text.Normalizer.normalize(genre.trim(), java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT);
        switch (name) {
            case "action": case "acao": case "accion": case "azione": return "Ação";
            case "adventure": case "aventura": case "aventure": case "avventura": return "Aventura";
            case "simulation": case "simulator": case "simulacao": case "simulacion": return "Simulação";
            case "indie": return "Indie";
            default: return genre;
        }
    }

    private void launchFromHome(int source, String key) {
        if (loadingGames) return;
        String[] snapshot = homeRows[source];
        for (int i = 0; i < snapshot.length; i += GAME_FIELDS) {
            if (!key.equals(snapshot[i + GAME_KEY])) continue;
            showLaunchDialog(snapshot, i, () -> resolveHomeLaunch(source, key));
            return;
        }
    }

    private void showLaunchDialog(String[] rows, int offset, Runnable start) {
        Dialog dialog = new Dialog(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(chrome(0xff20172d, 26));
        scroll.setClipToOutline(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.addView(body);
        LinearLayout summary = new LinearLayout(this);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        PosterImage artwork = new PosterImage();
        bindPoster(artwork, rows, offset);
        summary.addView(artwork, new LinearLayout.LayoutParams(dp(70), dp(100)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, 0, 0);
        TextView title = text(rows[offset], 21, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        labels.addView(title);
        if (!rows[offset + DETAILS].isEmpty()) labels.addView(text(rows[offset + DETAILS], 14, MUTED));
        summary.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(summary);
        boolean premium = !rows[offset + PREMIUM].isEmpty() && !libraryInfo[3].isEmpty();
        if (premium) {
            TextView info = text("A Amazon pode exigir Luna Premium para iniciar.", 15, MUTED);
            info.setPadding(0, dp(12), 0, 0);
            body.addView(info);
        }
        TextView framing = text("Enquadramento ›", 14, LIBRARY_SELECTED);
        framing.setMinHeight(dp(48));
        framing.setGravity(Gravity.CENTER_VERTICAL);
        framing.setBackground(tapBackground(Color.TRANSPARENT, 12));
        framing.setFocusable(true);
        framing.setOnClickListener(v -> showFramingSettings());
        body.addView(framing);
        LinearLayout actions = new LinearLayout(this);
        LinearLayout.LayoutParams actionSize = new LinearLayout.LayoutParams(-1, -2);
        actionSize.topMargin = dp(8);
        body.addView(actions, actionSize);
        actions.addView(libraryAction("Cancelar", false, dialog::dismiss),
            new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams startSize = new LinearLayout.LayoutParams(0, -2, 1);
        startSize.leftMargin = dp(8);
        actions.addView(libraryAction("Iniciar", true, () -> {
            dialog.dismiss();
            start.run();
        }), startSize);
        showLibraryDialog(dialog, scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            WindowManager.LayoutParams params = window.getAttributes();
            params.y = dp(12);
            window.setAttributes(params);
        }
    }

    private void resolveHomeLaunch(int source, String key) {
        if (loadingGames || !home || !libraryVisible) return;
        loadingGames = true;
        long token = ++operation;
        if (homeScroll != null) homeScrollPosition = homeScroll.getScrollY();
        worker.execute(() -> {
            try {
                String[] rows = nativeSection(source);
                post(token, () -> {
                    loadingGames = false;
                    for (int offset = 0; offset < rows.length; offset += GAME_FIELDS) {
                        if (!key.equals(rows[offset + GAME_KEY])) continue;
                        games = rows;
                        showPlayer(offset / GAME_FIELDS);
                        return;
                    }
                    libraryStatus.setText("Este jogo não está mais disponível.");
                });
            } catch (Exception error) {
                post(token, () -> {
                    loadingGames = false;
                    libraryStatus.setText(message(error));
                });
            }
        });
    }

    private void showLibrary(String[] library) {
        home = false;
        settingsScreen = false;
        games = library;
        loadingGames = false;
        libraryVisible = true;
        listLayout = getPreferences(MODE_PRIVATE).getBoolean(LIST_LAYOUT_PREF, false);
        LinearLayout content = libraryPage();
        if (inlineSearch) addInlineSearch(content);
        libraryStatus = text("", 15, MUTED);
        libraryStatus.setPadding(dp(16), dp(4), dp(16), dp(8));
        content.addView(libraryStatus);
        grid = new GridView(this);
        configurePosterGrid(grid, libraryColumns());
        grid.setChoiceMode(GridView.CHOICE_MODE_SINGLE);
        grid.setSelector(rounded(0x55ae9aff, 12));
        grid.setDrawSelectorOnTop(true);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (loadingGames) return;
            selectGame(visiblePositions[position]);
            launchSelection();
        });
        content.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        addBottomBar(content, false);
        renderGames();
        enterLibraryView(grid, 0, 10);
    }

    private void configurePosterGrid(GridView view, int columns) {
        view.setNumColumns(columns);
        view.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        view.setHorizontalSpacing(dp(10));
        view.setVerticalSpacing(dp(14));
        view.setPadding(dp(16), dp(4), dp(16), dp(16));
        view.setClipToPadding(false);
    }

    private int libraryColumns() {
        if (listLayout) return 1;
        return libraryColumnsForWidth();
    }

    // OpenNOW's poster grid uses fixed columns resolved from the available width and gutters.
    private int libraryColumnsForWidth() {
        int width = getResources().getConfiguration().screenWidthDp;
        int targetWidth = width >= 600 ? 118 : 92;
        return Math.max(2, Math.min(8, (width - 32 + 12) / (targetWidth + 12)));
    }

    private void showLibraryFilters() {
        if (loadingGames) return;
        Dialog dialog = new Dialog(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(chrome(0xff20172d, 26));
        scroll.setClipToOutline(true);
        LinearLayout options = new LinearLayout(this);
        options.setPadding(dp(20), dp(18), dp(20), dp(16));
        options.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(options);
        TextView title = heading("Ordenar e filtrar");
        title.setTextSize(23);
        options.addView(title);
        TextView orderLabel = text("Ordem", 15, LIBRARY_SELECTED);
        orderLabel.setPadding(0, dp(16), 0, dp(4));
        options.addView(orderLabel);

        android.widget.RadioGroup order = new android.widget.RadioGroup(this);
        String[] orderLabels = {"Ordem da Luna", "A a Z", "Z a A"};
        for (int i = 0; i < orderLabels.length; ++i) {
            android.widget.RadioButton choice = new android.widget.RadioButton(this);
            choice.setId(i + 1);
            choice.setText(orderLabels[i]);
            choice.setTextSize(16);
            choice.setMinHeight(dp(48));
            choice.setButtonTintList(ColorStateList.valueOf(LIBRARY_SELECTED));
            choice.setBackground(tapBackground(Color.TRANSPARENT, 12));
            order.addView(choice);
        }
        order.check(catalogSort + 1);
        options.addView(order);
        TextView sectionLabel = text("Seção", 15, LIBRARY_SELECTED);
        sectionLabel.setPadding(0, dp(12), 0, dp(4));
        options.addView(sectionLabel);
        Spinner filter = new Spinner(this);
        filter.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, SECTIONS));
        filter.setSelection(section);
        options.addView(filter, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout actions = new LinearLayout(this);
        LinearLayout.LayoutParams actionSize = new LinearLayout.LayoutParams(-1, -2);
        actionSize.topMargin = dp(14);
        options.addView(actions, actionSize);
        actions.addView(libraryAction("Cancelar", false, dialog::dismiss),
            new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams applySize = new LinearLayout.LayoutParams(0, -2, 1);
        applySize.leftMargin = dp(8);
        actions.addView(libraryAction("Aplicar", true, () -> {
            dialog.dismiss();
            catalogSort = order.getCheckedRadioButtonId() - 1;
            int selected = filter.getSelectedItemPosition();
            if (!home && !settingsScreen && selected == section) renderGames();
            else {
                inlineSearch = false;
                loadSection(selected);
            }
        }), applySize);
        showLibraryDialog(dialog, scroll);
    }

    private void showLibraryDialog(Dialog dialog, ScrollView scroll) {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(scroll);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            int width = Math.max(1, Math.min(480, getResources().getConfiguration().screenWidthDp - 32));
            window.setLayout(dp(width), WindowManager.LayoutParams.WRAP_CONTENT);
            window.setDimAmount(0.72f);
        }
        enterLibraryView(scroll, 0, 18);
    }

    private void showSettings() {
        if (loadingGames) return;
        settingsScreen = true;
        libraryVisible = true;
        grid = null;
        LinearLayout page = libraryPage();
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(12), dp(16), dp(12));
        libraryStatus = text("", 14, MUTED);
        body.addView(libraryStatus);
        TextView title = heading("Configurações");
        title.setPadding(0, 0, 0, dp(12));
        body.addView(title);
        addSetting(body, "account", libraryInfo[0].isEmpty() ? "Conta Amazon" : libraryInfo[0],
            libraryInfo[2].isEmpty() ? "Perfil Luna e sessão" : libraryInfo[2], this::showAccount);
        addSetting(body, "grid", "Interface", "Alternar entre grade de capas e lista simples", () -> {
            boolean list = getPreferences(MODE_PRIVATE).getBoolean(LIST_LAYOUT_PREF, false);
            getPreferences(MODE_PRIVATE).edit().putBoolean(LIST_LAYOUT_PREF, !list).apply();
            inlineSearch = false;
            loadSection(ALL_GAMES);
        });
        addSetting(body, "play", "Enquadramento", "Ajustar, preencher ou esticar a imagem", this::showFramingSettings);
        addSetting(body, "action", "Idioma dos jogos", "Preferir português nas partidas", this::showGameLanguage);
        addSetting(body, "filter", "Catálogo", "Atualizar jogos ou ordenar a Biblioteca", this::showLibraryMenu);
        addSetting(body, "trophy", "Luna Premium", "Ver os jogos do plano Premium", () -> loadSection(PREMIUM_GAMES));
        scroll.addView(body);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        addBottomBar(page, false);
        enterLibraryView(scroll, 0, 12);
    }

    private void showFramingSettings() {
        PlayerFraming[] modes = PlayerFraming.values();
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; ++i) labels[i] = modes[i].label;
        PlayerFraming current = PlayerFraming.fromPreference(
            getPreferences(MODE_PRIVATE).getString("player_framing", ""));
        new AlertDialog.Builder(this).setTitle("Enquadramento")
            .setSingleChoiceItems(labels, current.ordinal(), (dialog, which) -> {
                getPreferences(MODE_PRIVATE).edit().putString("player_framing", modes[which].name()).apply();
                dialog.dismiss();
            }).setNegativeButton("Fechar", null).show();
    }

    private void addSetting(LinearLayout body, String symbol, String label, String description, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(chrome(LIBRARY_CARD, 18));
        row.setForeground(tapBackground(Color.TRANSPARENT, 18));
        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackground(rounded(0xff3b2b51, 13));
        if ("account".equals(symbol) && !libraryInfo[1].isEmpty()) {
            iconBox.addView(coverView(libraryInfo[1], 13), new FrameLayout.LayoutParams(-1, -1));
        } else {
            iconBox.addView(new LibraryIcon(symbol, LIBRARY_SELECTED),
                new FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER));
        }
        row.addView(iconBox, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(12), 0, dp(8), 0);
        copy.addView(text(label, 17, Color.WHITE));
        copy.addView(text(description, 14, MUTED));
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(text("›", 22, ACCENT));
        row.setFocusable(true);
        row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = dp(10);
        body.addView(row, size);
    }

    private String libraryNetwork() {
        if (connectivity == null) return "";
        Network active = connectivity.getActiveNetwork();
        NetworkCapabilities network = active == null ? null : connectivity.getNetworkCapabilities(active);
        if (network == null) return "Sem conexão";
        if (network.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
        if (network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
        if (network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "Dados móveis";
        return "";
    }

    private void renderGames() {
        int count = games.length / GAME_FIELDS;
        String label = search.isEmpty() ? SECTIONS[section] : "Resultados";
        String status = label + " · " + (count == 0 ? "Nenhum jogo encontrado" :
            count + (count == 1 ? " jogo" : " jogos"));
        if (search.isEmpty() && section == PREMIUM_GAMES && !libraryInfo[3].isEmpty())
            status += " · Requer Luna Premium";
        libraryStatus.setText(status);
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; ++i) order[i] = i;
        if (catalogSort != 0) {
            java.text.Collator titles = java.text.Collator.getInstance(
                java.util.Locale.forLanguageTag("pt-BR"));
            java.util.Arrays.sort(order, (a, b) -> catalogSort == 1
                ? titles.compare(games[a * GAME_FIELDS], games[b * GAME_FIELDS])
                : titles.compare(games[b * GAME_FIELDS], games[a * GAME_FIELDS]));
        }
        visiblePositions = new int[count];
        String[] rows = new String[games.length];
        int highlighted = 0;
        for (int i = 0; i < count; ++i) {
            visiblePositions[i] = order[i];
            if (order[i] == selection) highlighted = i;
            System.arraycopy(games, order[i] * GAME_FIELDS, rows, i * GAME_FIELDS, GAME_FIELDS);
        }
        grid.setAdapter(new GameAdapter(rows, listLayout));
        if (count != 0) {
            grid.setSelection(highlighted);
            selectGame(visiblePositions[highlighted]);
        } else selection = 0;
    }

    private void selectGame(int position) {
        if (position < 0 || position >= games.length / GAME_FIELDS) return;
        selection = position;
        for (int i = 0; i < visiblePositions.length; ++i) {
            if (visiblePositions[i] == position) {
                grid.setItemChecked(i, true);
                break;
            }
        }
    }

    private void launchSelection() {
        if (!libraryVisible || home || settingsScreen || loadingGames || games.length == 0) return;
        int offset = selection * GAME_FIELDS;
        String key = games[offset + GAME_KEY];
        long token = operation;
        showLaunchDialog(games, offset, () -> {
            if (isCancelled(token) || !libraryVisible || home || settingsScreen) return;
            // Catalog updates can reorder games while the launch dialog is open.
            for (int i = 0; i < games.length; i += GAME_FIELDS) {
                if (key.equals(games[i + GAME_KEY])) {
                    if (!loadingGames) showPlayer(i / GAME_FIELDS);
                    else libraryStatus.setText("A biblioteca está atualizando. Tente de novo.");
                    return;
                }
            }
            libraryStatus.setText("Este jogo não está mais disponível.");
        });
    }

    private void loadGames(String query) {
        if (loadingGames) return;
        loadingGames = true;
        libraryStatus.setText("Carregando jogos…");
        long token = ++operation;
        worker.execute(() -> {
            try {
                String[] result = nativeSearch(query);
                post(token, () -> {
                    search = query;
                    section = ALL_GAMES;
                    selection = 0;
                    showLibrary(result);
                });
            } catch (Exception error) {
                post(token, () -> {
                    loadingGames = false;
                    libraryStatus.setText(message(error));
                });
            }
        });
    }

    private void loadSection(int selected) {
        if (loadingGames) return;
        loadingGames = true;
        libraryStatus.setText("Carregando jogos…");
        long token = ++operation;
        worker.execute(() -> {
            try {
                String[] result = nativeSection(selected);
                post(token, () -> {
                    section = selected;
                    search = "";
                    selection = 0;
                    showLibrary(result);
                });
            } catch (Exception error) {
                post(token, () -> {
                    loadingGames = false;
                    libraryStatus.setText(message(error));
                });
            }
        });
    }

    private boolean canExploreCatalog(long generation) {
        return !destroyed && !catalogPaused && catalogGeneration == generation;
    }

    private void startCatalogDiscovery(boolean force) {
        catalogPaused = false;
        long generation = ++catalogGeneration;
        catalogWorker.execute(() -> {
            if (!canExploreCatalog(generation)) return;
            try {
                nativeExplore(generation, force);
            } catch (Exception ignored) {
                // The available catalog remains usable. The account menu can retry discovery.
            } finally {
                ui.post(() -> refreshDiscoveredGames(generation));
            }
        });
    }

    // Called by the explorer when it adds a batch. Coalesce refreshes on the UI thread.
    private void catalogBatchAdded(long generation) {
        ui.post(() -> {
            if (!canExploreCatalog(generation) || catalogRefreshPending) return;
            catalogRefreshPending = true;
            ui.postDelayed(() -> {
                catalogRefreshPending = false;
                refreshDiscoveredGames(generation);
            }, 2500);
        });
    }

    private void refreshDiscoveredGames(long generation) {
        if (!canExploreCatalog(generation) || !libraryVisible || loadingGames || settingsScreen) return;
        if (home) {
            refreshHomeRows();
            return;
        }
        if (!search.isEmpty() || (section != ALL_GAMES && section != PREMIUM_GAMES)) return;
        loadingGames = true;
        long token = operation;
        int visibleSection = section;
        worker.execute(() -> {
            try {
                String[] rows = nativeSection(visibleSection);
                post(token, () -> {
                    loadingGames = false;
                    if (java.util.Arrays.equals(games, rows)) return;
                    String selectedKey = games.length == 0 ? "" : games[selection * GAME_FIELDS + GAME_KEY];
                    for (int i = 0; i < rows.length; i += GAME_FIELDS) {
                        if (rows[i + GAME_KEY].equals(selectedKey)) {
                            selection = i / GAME_FIELDS;
                            break;
                        }
                    }
                    games = rows;
                    renderGames();
                });
            } catch (Exception error) {
                post(token, () -> loadingGames = false);
            }
        });
    }

    private void refreshHomeRows() {
        long token = operation;
        worker.execute(() -> {
            if (isCancelled(token)) return;
            try {
                String[][] rows = readHomeRows();
                post(token, () -> {
                    if (libraryVisible && home && !loadingGames &&
                        !java.util.Arrays.deepEquals(homeRows, rows)) showHome(rows, true);
                });
            } catch (Exception ignored) {
                // Keep the current home snapshot when a refresh fails.
            }
        });
    }

    private void showLibraryMenu() {
        if (loadingGames) return;
        new AlertDialog.Builder(this)
            .setTitle("Menu da biblioteca")
            .setItems(new String[]{"Recarregar biblioteca", "Carregar mais jogos",
                listLayout ? "Mostrar grade de capas" : "Mostrar lista simples", "Idioma dos jogos",
                "Luna Premium", "Sair do LunaNX"}, (dialog, which) -> {
                if (which == 0) refreshLibrary();
                else if (which == 1) {
                    loadSection(ALL_GAMES);
                    startCatalogDiscovery(true);
                } else if (which == 2) {
                    getPreferences(MODE_PRIVATE).edit().putBoolean(LIST_LAYOUT_PREF, !listLayout).apply();
                    if (home || settingsScreen) loadSection(ALL_GAMES);
                    else showLibrary(games);
                } else if (which == 3) showGameLanguage();
                else if (which == 4) loadSection(PREMIUM_GAMES);
                else finish();
            }).show();
    }

    private void showAccount() {
        if (loadingGames) return;
        long token = operation;
        worker.execute(() -> {
            String[] info = nativeLibraryInfo();
            post(token, () -> {
                if (!libraryVisible) return;
                String title = info[0].isEmpty() ? "Conta Amazon" : info[0];
                String plan = info[2].isEmpty() ? "Plano Luna não identificado" : info[2];
                new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(plan + "\nLoja da conta: " + info[4] +
                        "\nJogos disponíveis: " + info[5] + "\n" + libraryNetwork())
                    .setPositiveButton("Fechar", null)
                    .setNeutralButton("Menu", (dialog, which) -> showLibraryMenu())
                    .setNegativeButton("Sair da conta", (dialog, which) -> signOut())
                    .show();
            });
        });
    }

    private void showGameLanguage() {
        boolean preferPortuguese = getPreferences(MODE_PRIVATE).getBoolean("prefer_pt_br", false);
        new AlertDialog.Builder(this)
            .setTitle("Idioma dos jogos")
            .setMessage("A preferência é enviada na próxima partida. Se o jogo continuar em inglês, procure Idioma, Áudio ou Legendas nas configurações do próprio jogo.")
            .setPositiveButton(preferPortuguese ? "Usar padrão do Luna" : "Preferir português (Brasil)",
                (dialog, which) -> getPreferences(MODE_PRIVATE).edit()
                    .putBoolean("prefer_pt_br", !preferPortuguese).apply())
            .setNegativeButton("Fechar", null)
            .show();
    }

    private void refreshLibrary() {
        libraryVisible = false;
        ++catalogGeneration;
        showLoading("Atualizando a biblioteca…");
        long token = ++operation;
        worker.execute(() -> {
            try {
                String[] result = nativeLoad();
                if (result == null) post(token, () -> showSignIn("A sessão expirou. Gere um novo código."));
                else openLibrary(token);
            } catch (Exception error) {
                post(token, () -> {
                    if (home && homeRows != null) showHome(homeRows);
                    else showLibrary(games);
                    libraryStatus.setText(message(error));
                });
            }
        });
    }

    private void signOut() {
        libraryVisible = false;
        ++catalogGeneration;
        showLoading("Encerrando a sessão Amazon…");
        long token = ++operation;
        worker.execute(() -> {
            try {
                nativeSignOut();
                post(token, () -> { search = ""; games = null; showSignIn(null); });
            } catch (Exception error) {
                post(token, () -> { search = ""; games = null; showSignIn(message(error)); });
            }
        });
    }

    private final class PosterImage extends ImageView {
        PosterImage() {
            super(MainActivity.this);
            setScaleType(ScaleType.CENTER_CROP);
            setBackground(rounded(0xff2b223d, 12));
            setClipToOutline(true);
        }
    }

    private void bindPoster(PosterImage view, String[] rows, int offset) {
        String url = rows[offset + COVER];
        bindCover(view, url.isEmpty() ? rows[offset + BANNER_FALLBACK] : url);
    }

    private final class PosterCard extends FrameLayout {
        private final PosterImage image;
        private final TextView title;

        PosterCard() {
            super(MainActivity.this);
            setBackground(rounded(LIBRARY_CARD, 13));
            setClipToOutline(true);
            setForeground(tapBackground(Color.TRANSPARENT, 13));
            image = new PosterImage();
            addView(image, new FrameLayout.LayoutParams(-1, -1));
            title = text("", 13, Color.WHITE);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setPadding(dp(7), dp(12), dp(7), dp(7));
            title.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00151220, 0xe8151220, 0xff151220}));
            title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(title, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        }

        void bind(String[] rows, int offset) {
            title.setText(rows[offset]);
            setContentDescription(rows[offset] + (rows[offset + PREMIUM].isEmpty() ? "" : ", Luna Premium"));
            bindPoster(image, rows, offset);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = View.MeasureSpec.getSize(widthSpec);
            if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.EXACTLY) {
                heightSpec = View.MeasureSpec.makeMeasureSpec(
                    Math.round(width * 888f / 628f), View.MeasureSpec.EXACTLY);
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    private final class GameAdapter extends BaseAdapter {
        final String[] rows;
        final boolean list;

        GameAdapter(String[] rows, boolean list) {
            this.rows = rows;
            this.list = list;
        }

        @Override public int getCount() { return rows.length / GAME_FIELDS; }
        @Override public Object getItem(int position) { return rows[position * GAME_FIELDS]; }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            if (!list) {
                PosterCard card = (PosterCard) recycled;
                if (card == null) {
                    card = new PosterCard();
                    card.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
                }
                card.bind(rows, position * GAME_FIELDS);
                return card;
            }
            LinearLayout tile = (LinearLayout) recycled;
            if (tile == null) {
                tile = new LinearLayout(MainActivity.this);
                tile.setOrientation(LinearLayout.HORIZONTAL);
                tile.setGravity(Gravity.CENTER_VERTICAL);
                tile.setPadding(dp(8), dp(4), dp(8), dp(4));
                tile.setBackground(rounded(LIBRARY_CARD, 12));
                PosterImage cover = new PosterImage();
                tile.addView(cover, new LinearLayout.LayoutParams(dp(48), dp(62)));
                LinearLayout labels = new LinearLayout(MainActivity.this);
                labels.setOrientation(LinearLayout.VERTICAL);
                labels.setPadding(dp(10), dp(4), 0, dp(4));
                tile.addView(labels, new LinearLayout.LayoutParams(-1, -2));
                TextView name = text("", 16, Color.WHITE);
                name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                name.setMaxLines(2);
                labels.addView(name);
                TextView badge = text("", 13, MUTED);
                badge.setSingleLine(true);
                labels.addView(badge);
            }
            int offset = position * GAME_FIELDS;
            LinearLayout labels = (LinearLayout) tile.getChildAt(1);
            ((TextView) labels.getChildAt(0)).setText(rows[offset]);
            TextView badge = (TextView) labels.getChildAt(1);
            boolean premium = !rows[offset + PREMIUM].isEmpty();
            badge.setText(premium && rows[offset + OFFER].isEmpty() ? "Luna Premium" : rows[offset + OFFER]);
            badge.setTextColor(premium ? 0xffffc44d : MUTED);
            bindPoster((PosterImage) tile.getChildAt(0), rows, offset);
            return tile;
        }
    }

    private void revealArtwork(ImageView view, Bitmap artwork) {
        view.setImageBitmap(artwork);
        if (!view.isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) return;
        view.setAlpha(0.3f);
        view.animate().alpha(1f).setDuration(200).start();
    }

    private void bindCover(ImageView image, String url) {
        if (url.equals(image.getTag())) return;
        image.animate().cancel();
        image.setAlpha(1f);
        image.setTag(url);
        Bitmap cached = covers.get(url);
        if (cached != null) image.setImageBitmap(cached);
        else image.setImageResource(R.drawable.lunanx_icon);
        if (url.isEmpty() || cached != null) return;
        artwork.execute(() -> {
            if (destroyed) return;
            Bitmap ready = covers.get(url);
            if (ready != null) {
                ui.post(() -> {
                    if (!destroyed && url.equals(image.getTag())) revealArtwork(image, ready);
                });
                return;
            }
            HttpsURLConnection connection = null;
            try {
                URL uri = new URL(url);
                String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
                if (!uri.getProtocol().equals("https") ||
                    !(host.equals("media-amazon.com") || host.endsWith(".media-amazon.com") ||
                      host.equals("images-amazon.com") || host.endsWith(".images-amazon.com"))) return;
                connection = (HttpsURLConnection) uri.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(3500);
                connection.setReadTimeout(3500);
                try (InputStream input = connection.getInputStream()) {
                    Bitmap decoded = BitmapFactory.decodeStream(input);
                    if (decoded == null) return;
                    covers.put(url, decoded);
                    ui.post(() -> {
                        if (!destroyed && url.equals(image.getTag())) revealArtwork(image, decoded);
                    });
                }
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private int selectedGame = -1;
    private boolean surfaceAdded;
    private volatile long surfaceRevision;
    private String videoSurfaceError;
    private int returnOrientation;
    private static final int TOUCH_LT = 0x10000;
    private static final int TOUCH_RT = 0x20000;

    private void showPlayer(int index) {
        libraryVisible = false;
        catalogPaused = true;
        ++catalogGeneration;
        selectedGame = index;
        playerTitle = games[index * GAME_FIELDS];
        playbackReport = new PlaybackReport(SystemClock.elapsedRealtime());
        showMetrics = false;
        videoSurfaceError = null;
        playerFraming = PlayerFraming.fromPreference(
            getPreferences(MODE_PRIVATE).getString("player_framing", ""));
        player = new FrameLayout(this);
        player.setBackgroundColor(Color.BLACK);
        setContentView(player);
        surfaceAdded = false;
        returnOrientation = getRequestedOrientation();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterPlayerFullscreen();
        refreshGamepads();
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE)
            attachPlayerSurface();
    }

    private void enterPlayerFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && player != null) enterPlayerFullscreen();
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (libraryVisible && !loadingGames) {
            if (settingsScreen) showSettings();
            else if (home) showHome(homeRows, true);
            else showLibrary(games);
        }
        if (player != null && config.orientation == Configuration.ORIENTATION_LANDSCAPE)
            attachPlayerSurface();
    }

    private FrameLayout.LayoutParams floating(int width, int height, int gravity, int margin) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height, gravity);
        params.setMargins(dp(margin), dp(margin), dp(margin), dp(margin));
        return params;
    }

    private void attachPlayerSurface() {
        if (surfaceAdded) return;
        surfaceAdded = true;
        long token = ++operation;
        FrameLayout current = player;
        SurfaceView surface = new SurfaceView(this);
        playerSurface = surface;
        surface.getHolder().setFixedSize(PlayerFraming.VIDEO_WIDTH, PlayerFraming.VIDEO_HEIGHT);
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                if (isCancelled(token)) return;
                Surface target = holder.getSurface();
                long revision = ++surfaceRevision;
                worker.execute(() -> attachPlaybackSurface(token, revision, target, 0));
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                if (isCancelled(token)) return;
                ++surfaceRevision;
                worker.execute(() -> {
                    if (!isCancelled(token) && playbackTicker != null && !playbackTicker.isDone())
                        nativeSetVideoSurface(null);
                });
            }
        });
        current.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        current.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (player == current) updateFraming();
        });
        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onDoubleTap(MotionEvent event) {
                showPlayerMenu();
                return true;
            }
        });
        current.setOnTouchListener((view, event) -> gestures.onTouchEvent(event));
        streamStatus = text("Iniciando " + playerTitle + "…", 16, Color.WHITE);
        streamStatus.setGravity(Gravity.CENTER);
        streamStatus.setPadding(dp(16), dp(8), dp(16), dp(8));
        streamStatus.setBackground(rounded(0xd91c2030, 12));
        current.addView(streamStatus, floating(-2, -2, Gravity.CENTER, 16));

        Button menu = playerAction("⋯", view -> showPlayerMenu());
        menu.setTextSize(24);
        menu.setContentDescription("Abrir menu do player");
        playerMenuButton = menu;
        current.addView(menu, floating(dp(54), dp(44), Gravity.TOP | Gravity.START, 12));

        metrics = text("", 13, Color.WHITE);
        metrics.setPadding(dp(12), dp(7), dp(12), dp(7));
        metrics.setBackground(rounded(0xdb10121c, 10));
        current.addView(metrics, floating(-2, -2, Gravity.TOP | Gravity.END, 12));

        controls = touchOverlay();
        current.addView(controls, new FrameLayout.LayoutParams(-1, -1));
        updatePlayerOverlays();
    }

    private void updateFraming() {
        if (player == null || playerSurface == null) return;
        int width = player.getWidth();
        int height = player.getHeight();
        playerSurface.setScaleX(playerFraming.scaleX(width, height));
        playerSurface.setScaleY(playerFraming.scaleY(width, height));
    }

    private Button playerAction(String label, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(action);
        button.setMinHeight(dp(44));
        button.setTextSize(14);
        button.setPadding(dp(12), dp(4), dp(12), dp(4));
        applyPlayerButtonStyle(button, false);
        return button;
    }

    private void applyPlayerButtonStyle(Button button, boolean selected) {
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, rounded(0xffd8ceff, 10));
        states.addState(new int[]{}, rounded(selected ? 0xff4e3a86 : 0xe3232638, 10));
        button.setBackground(states);
        button.setTextColor(new ColorStateList(
            new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
            new int[]{BACKGROUND, Color.WHITE}));
    }

    private void showPlayerMenu() {
        if (player == null || playerMenu != null) return;
        resetPad();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(14), dp(18), dp(14));
        content.setBackground(rounded(BACKGROUND, 16));

        TextView title = heading(playerTitle);
        title.setTextSize(21);
        title.setMaxLines(2);
        content.addView(title);
        TextView label = text("Enquadramento", 13, MUTED);
        label.setPadding(0, dp(10), 0, dp(5));
        content.addView(label);

        LinearLayout framing = new LinearLayout(this);
        content.addView(framing);
        TextView description = text(playerFraming.description, 12, MUTED);
        description.setPadding(0, dp(4), 0, dp(8));
        Button[] options = new Button[PlayerFraming.values().length];
        for (PlayerFraming option : PlayerFraming.values()) {
            Button item = playerAction(option.label, view -> {
                playerFraming = option;
                getPreferences(MODE_PRIVATE).edit().putString("player_framing", option.name()).apply();
                for (PlayerFraming choice : PlayerFraming.values()) {
                    applyPlayerButtonStyle(options[choice.ordinal()], choice == option);
                }
                description.setText(option.description);
                updateFraming();
            });
            options[option.ordinal()] = item;
            applyPlayerButtonStyle(item, option == playerFraming);
            LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(0, dp(44), 1);
            position.setMargins(0, 0, dp(5), 0);
            framing.addView(item, position);
        }
        content.addView(description);
        LinearLayout actions = new LinearLayout(this);
        content.addView(actions);
        Button diagnostics = playerAction(metricsAction(), view -> {
            showMetrics = !showMetrics;
            ((Button) view).setText(metricsAction());
            updatePlayerOverlays();
        });
        actions.addView(diagnostics, new LinearLayout.LayoutParams(0, dp(44), 1));
        if (!gamepadConnected) {
            Button touch = playerAction(touchAction(), view -> {
                touchControls = !touchControls;
                ((Button) view).setText(touchAction());
                updatePlayerOverlays();
            });
            LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(0, dp(44), 1);
            position.setMarginStart(dp(5));
            actions.addView(touch, position);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setView(scroll)
            .setNegativeButton("Sair do jogo", (ignored, which) -> leavePlayer())
            .setPositiveButton("Continuar", (ignored, which) -> {})
            .create();
        playerMenu = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (playerMenu == dialog) playerMenu = null;
            if (player != null) enterPlayerFullscreen();
        });
        dialog.setOnKeyListener((ignored, key, event) -> {
            if (key == KeyEvent.KEYCODE_BUTTON_B) {
                if (event.getAction() == KeyEvent.ACTION_UP) dialog.dismiss();
                return true;
            }
            if (key == KeyEvent.KEYCODE_BUTTON_A) {
                if (event.getAction() == KeyEvent.ACTION_UP && dialog.getCurrentFocus() != null)
                    dialog.getCurrentFocus().performClick();
                return true;
            }
            return false;
        });
        dialog.show();
        options[playerFraming.ordinal()].requestFocus();
    }

    private String metricsAction() {
        return showMetrics ? "Ocultar métricas" : "Mostrar métricas";
    }

    private String touchAction() {
        return touchControls ? "Ocultar controles" : "Mostrar controles";
    }

    private void updatePlayerOverlays() {
        if (controls == null || player == null) return;
        boolean showTouch = !gamepadConnected && touchControls;
        controls.setVisibility(showTouch ? View.VISIBLE : View.GONE);
        if (!showTouch) resetTouchPad();
        playerMenuButton.setVisibility(gamepadConnected ? View.GONE : View.VISIBLE);
        metrics.setVisibility(showMetrics ? View.VISIBLE : View.GONE);
    }

    private static boolean isGamepad(InputDevice device) {
        if (device == null || device.isVirtual()) return false;
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private void refreshGamepads() {
        if (player == null) return;
        boolean wasConnected = gamepadConnected;
        if (!isGamepad(InputDevice.getDevice(controllerDevice))) {
            if (controllerDevice != -1) resetPad();
            controllerDevice = -1;
            for (int id : InputDevice.getDeviceIds()) {
                if (isGamepad(InputDevice.getDevice(id))) {
                    controllerDevice = id;
                    break;
                }
            }
        }
        gamepadConnected = controllerDevice != -1;
        if (!wasConnected && gamepadConnected) {
            showMetrics = false;
            if (playerMenu != null) playerMenu.dismiss();
        }
        updatePlayerOverlays();
    }

    private void resetTouchPad() {
        touchButtons = 0;
        touchLx = touchLy = touchRx = touchRy = 0;
        publishPad();
    }

    private void resetPad() {
        padButtons = hatButtons = 0;
        lx = ly = rx = ry = leftTrigger = rightTrigger = 0;
        resetTouchPad();
    }

    private void attachPlaybackSurface(long token, long revision, Surface target, int attempt) {
        if (isCancelled(token) || revision != surfaceRevision || !target.isValid()) return;
        if (playbackTicker == null) {
            startPlayback(token, target);
            return;
        }
        if (playbackTicker.isDone()) return;
        if (nativeSetVideoSurface(target)) {
            post(token, () -> {
                if (revision == surfaceRevision) videoSurfaceError = null;
            });
        } else if (attempt < 2) {
            worker.schedule(() -> attachPlaybackSurface(token, revision, target, attempt + 1),
                250, TimeUnit.MILLISECONDS);
        } else {
            post(token, () -> {
                if (revision != surfaceRevision) return;
                videoSurfaceError = "A partida continua, mas o Android não liberou a tela do vídeo.";
                streamStatus.setText(videoSurfaceError);
                streamStatus.setVisibility(View.VISIBLE);
            });
        }
    }

    private void startPlayback(long token, Surface target) {
        if (isCancelled(token)) return;
        try {
            boolean preferPortuguese = getPreferences(MODE_PRIVATE).getBoolean("prefer_pt_br", false);
            nativeStart(selectedGame, target, preferPortuguese);
            PlaybackReport report = playbackReport;
            playbackTicker = worker.scheduleWithFixedDelay(new Runnable() {
                long checkedAt;
                long sampledAt;
                long lastBytes;
                long lastReceived;
                long lastDecoded;
                String metricsLabel = "";
                int previousRumble;
                @Override public void run() {
                    if (isCancelled(token)) return;
                    Pad input = pad;
                    int rumble = nativeTick(input.buttons, input.lt, input.rt, input.x, input.y, input.z, input.rz);
                    if (rumble != previousRumble) {
                        previousRumble = rumble;
                        post(token, () -> updateRumble(rumble));
                    }
                    long now = SystemClock.elapsedRealtime();
                    if (now - checkedAt < 250) return;
                    checkedAt = now;
                    String[] status = nativeStatus();
                    if (status == null) return;
                    int phase = Integer.parseInt(status[0]);
                    long bytes = Long.parseLong(status[2]);
                    long received = Long.parseLong(status[4]);
                    long decoded = Long.parseLong(status[5]);
                    if (sampledAt == 0 || now - sampledAt >= 1000 || phase >= ENDED) {
                        report.sample(now, phase, bytes, received, decoded, status);
                        long elapsed = now - sampledAt;
                        if (sampledAt != 0) {
                            double mbps = (bytes - lastBytes) * 0.008 / elapsed;
                            long receivedFps = Math.round((received - lastReceived) * 1000.0 / elapsed);
                            long decodedFps = Math.round((decoded - lastDecoded) * 1000.0 / elapsed);
                            metricsLabel = String.format(java.util.Locale.ROOT,
                                "FPS entra %d / dec %d · %.1f Mb/s\nFila %s ms · var. %s ms · desc. RTP %s\nResets %s · codec %s · fila cheia %s\nNo codec: %s quadros · %s ms",
                                receivedFps, decodedFps, mbps, status[8], status[9],
                                status[6], status[7], status[10], status[11], status[12], status[13]);
                        }
                        sampledAt = now;
                        lastBytes = bytes;
                        lastReceived = received;
                        lastDecoded = decoded;
                    }
                    String label = metricsLabel;
                    post(token, () -> {
                        boolean showSurfaceError = phase < ENDED && videoSurfaceError != null;
                        streamStatus.setVisibility(phase == STREAMING && !showSurfaceError ? View.GONE : View.VISIBLE);
                        streamStatus.setText(showSurfaceError ? videoSurfaceError : status[1]);
                        metrics.setText(label);
                    });
                    if (phase >= ENDED) {
                        nativeStop();
                        playbackTicker.cancel(false);
                    }
                }
            }, 0, 16, TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            post(token, () -> streamStatus.setText(message(error)));
        }
    }

    private void leavePlayer() {
        if (player == null) return;
        PlaybackReport report = playbackReport;
        ++operation;
        player = null;
        if (playerMenu != null) playerMenu.dismiss();
        playerSurface = null;
        controls = null;
        playerMenuButton = null;
        updateRumble(0);
        controllerDevice = -1;
        gamepadConnected = false;
        resetPad();
        stopPlaybackAsync(report);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(0);
        setRequestedOrientation(returnOrientation);
        if (home) {
            showHome(homeRows);
            refreshHomeRows();
        } else {
            showLibrary(games);
            if (section == RECENT_GAMES) loadSection(RECENT_GAMES);
        }
        startCatalogDiscovery(false);
    }

    private void stopPlaybackAsync(PlaybackReport report) {
        worker.execute(() -> {
            if (playbackTicker != null) playbackTicker.cancel(false);
            playbackTicker = null;
            long now = SystemClock.elapsedRealtime();
            if (report != null) {
                String[] status = nativeStatus();
                if (status != null) {
                    report.sample(now, Integer.parseInt(status[0]), Long.parseLong(status[2]),
                        Long.parseLong(status[4]), Long.parseLong(status[5]), status);
                }
            }
            nativeStop();
            if (report == null) return;
            String summary = report.format(now);
            ui.post(() -> {
                if (!destroyed && player == null && libraryVisible && playbackReport == report)
                    showPlaybackReport(summary);
            });
        });
    }

    private void showPlaybackReport(String summary) {
        Dialog dialog = new Dialog(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(chrome(0xff20172d, 26));
        scroll.setClipToOutline(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(16));
        scroll.addView(panel);
        TextView report = text(summary, 14, Color.WHITE);
        report.setTextIsSelectable(true);
        report.setPadding(0, 0, 0, dp(16));
        panel.addView(report);
        panel.addView(libraryAction("Copiar relatório", true, () -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Relatório de partida LunaNX", summary));
                dialog.dismiss();
            }
        }));
        LinearLayout.LayoutParams close = new LinearLayout.LayoutParams(-1, -2);
        close.topMargin = dp(8);
        panel.addView(libraryAction("Fechar", false, dialog::dismiss), close);
        showLibraryDialog(dialog, scroll);
    }

    // Classic touch skin geometry adapted from OpenNOW Android (Zortos, MIT).
    private View touchOverlay() {
        FrameLayout panel = new FrameLayout(this);
        panel.setMotionEventSplittingEnabled(true);
        for (boolean left : new boolean[]{true, false}) {
            LinearLayout side = new LinearLayout(this);
            side.setOrientation(LinearLayout.VERTICAL);
            side.setGravity(Gravity.CENTER_HORIZONTAL);
            side.setMotionEventSplittingEnabled(true);
            side.addView(left
                ? padRow(new String[]{"LT", "LB", "L3"}, new int[]{TOUCH_LT, 0x100, 0x40}, true)
                : padRow(new String[]{"R3", "RB", "RT"}, new int[]{0x80, 0x200, TOUCH_RT}, true));
            LinearLayout primary = new LinearLayout(this);
            primary.setGravity(Gravity.CENTER_VERTICAL);
            primary.setMotionEventSplittingEnabled(true);
            TouchStick stick = new TouchStick(left);
            if (left) primary.addView(stick, new LinearLayout.LayoutParams(dp(86), dp(86)));
            if (left) primary.addView(new TouchDpad(), new LinearLayout.LayoutParams(dp(98), dp(98)));
            else {
                LinearLayout face = new LinearLayout(this);
                face.setOrientation(LinearLayout.VERTICAL);
                face.setMotionEventSplittingEnabled(true);
                face.addView(padRow(new String[]{"Y"}, new int[]{0x8000}, false));
                face.addView(padRow(new String[]{"X", "B"}, new int[]{0x4000, 0x2000}, false));
                face.addView(padRow(new String[]{"A"}, new int[]{0x1000}, false));
                primary.addView(face);
            }
            if (!left) primary.addView(stick, new LinearLayout.LayoutParams(dp(86), dp(86)));
            side.addView(primary);
            panel.addView(side, floating(-2, -2, Gravity.BOTTOM | (left ? Gravity.START : Gravity.END), 10));
        }
        panel.addView(padRow(new String[]{"View", "Menu"}, new int[]{0x20, 0x10}, true),
            floating(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 10));
        return panel;
    }

    private LinearLayout padRow(String[] labels, int[] masks, boolean shoulder) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setMotionEventSplittingEnabled(true);
        for (int i = 0; i < labels.length; i++) {
            int mask = masks[i];
            TextView key = text(labels[i], shoulder ? 12 : 16, Color.WHITE);
            key.setGravity(Gravity.CENTER);
            android.graphics.drawable.StateListDrawable background = new android.graphics.drawable.StateListDrawable();
            background.addState(new int[]{android.R.attr.state_pressed}, padShape(0xe56d4ba5));
            background.addState(new int[]{}, padShape(0x66181724));
            key.setBackground(background);
            key.setOnTouchListener((view, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.setPressed(true);
                        touchButtons |= mask;
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.setPressed(false);
                        touchButtons &= ~mask;
                        break;
                    default: return true;
                }
                publishPad();
                return true;
            });
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(dp(shoulder ? 51 : 42),
                dp(shoulder ? 35 : 42));
            layout.setMargins(dp(3), dp(1), dp(3), dp(1));
            row.addView(key, layout);
        }
        return row;
    }

    private GradientDrawable padShape(int fill) {
        GradientDrawable shape = rounded(fill, 30);
        shape.setStroke(dp(1), 0xcce9ddff);
        return shape;
    }

    private final class TouchDpad extends View {
        private final Path cross = new Path();
        private final Path active = new Path();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int directions;

        TouchDpad() {
            super(MainActivity.this);
            setContentDescription("Direcional");
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            float arm = Math.min(width, height) / 3.1f;
            float inset = dp(1);
            cross.reset();
            cross.addRoundRect((width - arm) / 2, inset, (width + arm) / 2, height - inset,
                dp(7), dp(7), Path.Direction.CW);
            Path horizontal = new Path();
            horizontal.addRoundRect(inset, (height - arm) / 2, width - inset, (height + arm) / 2,
                dp(7), dp(7), Path.Direction.CW);
            cross.op(horizontal, Path.Op.UNION);
        }

        @Override protected void onDraw(Canvas canvas) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x66181724);
            canvas.drawPath(cross, paint);
            float width = getWidth(), height = getHeight();
            float x = width / 2, y = height / 2;
            canvas.save();
            canvas.clipPath(cross);
            paint.setColor(0xe56d4ba5);
            int[] masks = {1, 8, 2, 4};
            for (int mask : masks) {
                if ((directions & mask) != 0) {
                    active.rewind();
                    active.moveTo(x, y);
                    active.lineTo(0, 0);
                    active.lineTo(width, 0);
                    active.close();
                    canvas.drawPath(active, paint);
                }
                canvas.rotate(90, x, y);
            }
            canvas.restore();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0xcce9ddff);
            canvas.drawPath(cross, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int next = 0;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    float x = (event.getX() - getWidth() / 2f) / getWidth();
                    float y = (event.getY() - getHeight() / 2f) / getHeight();
                    if (Math.abs(x) > 0.14f && Math.abs(x) >= 0.52f * Math.abs(y))
                        next |= x < 0 ? 4 : 8;
                    if (Math.abs(y) > 0.14f && Math.abs(y) >= 0.52f * Math.abs(x))
                        next |= y < 0 ? 1 : 2;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    break;
                default: return true;
            }
            directions = next;
            touchButtons = (touchButtons & ~15) | directions;
            publishPad();
            invalidate();
            return true;
        }
    }

    private final class TouchStick extends View {
        final boolean left;
        final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float x;
        float y;
        TouchStick(boolean left) {
            super(MainActivity.this);
            this.left = left;
            setContentDescription(left ? "Analógico esquerdo" : "Analógico direito");
        }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0xcce9ddff);
            canvas.drawCircle(cx, cy, Math.min(cx, cy) - dp(2), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x995f458a);
            canvas.drawCircle(cx + x * dp(24), cy - y * dp(24), dp(17), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xcce9ddff);
            canvas.drawCircle(cx + x * dp(24), cy - y * dp(24), dp(17), paint);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    float rawX = (event.getX() - getWidth() / 2f) / dp(28);
                    float rawY = (getHeight() / 2f - event.getY()) / dp(28);
                    float scale = Math.max(1f, (float) Math.hypot(rawX, rawY));
                    x = rawX / scale;
                    y = rawY / scale;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    x = y = 0;
                    break;
                default: return true;
            }
            if (left) { touchLx = x; touchLy = y; }
            else { touchRx = x; touchRy = y; }
            publishPad();
            invalidate();
            return true;
        }
    }

    private void publishPad() {
        pad = new Pad((padButtons | hatButtons | touchButtons) & 0xffff,
            (touchButtons & TOUCH_LT) != 0 ? 1 : leftTrigger,
            (touchButtons & TOUCH_RT) != 0 ? 1 : rightTrigger,
            touchLx == 0 ? lx : touchLx,
            touchLy == 0 ? ly : touchLy,
            touchRx == 0 ? rx : touchRx,
            touchRy == 0 ? ry : touchRy);
    }

    private static int buttonMask(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP: return 1;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 2;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 4;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 8;
            case KeyEvent.KEYCODE_BUTTON_START: return 0x10;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return 0x20;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return 0x40;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return 0x80;
            case KeyEvent.KEYCODE_BUTTON_L1: return 0x100;
            case KeyEvent.KEYCODE_BUTTON_R1: return 0x200;
            case KeyEvent.KEYCODE_BUTTON_MODE: return 0x400;
            case KeyEvent.KEYCODE_BUTTON_A: return 0x1000;
            case KeyEvent.KEYCODE_BUTTON_B: return 0x2000;
            case KeyEvent.KEYCODE_BUTTON_X: return 0x4000;
            case KeyEvent.KEYCODE_BUTTON_Y: return 0x8000;
            default: return 0;
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (libraryVisible && isGamepad(event.getDevice()) && !(getCurrentFocus() instanceof EditText) &&
            handleLibraryController(event)) return true;
        if (player != null && event.getAction() != KeyEvent.ACTION_MULTIPLE) {
            if (isGamepad(event.getDevice()) && controllerDevice != event.getDeviceId()) {
                resetPad();
                controllerDevice = event.getDeviceId();
                refreshGamepads();
            }
            boolean pressed = event.getAction() == KeyEvent.ACTION_DOWN;
            int key = event.getKeyCode();
            int mask = buttonMask(key);
            if (mask != 0) {
                if (pressed) padButtons |= mask;
                else padButtons &= ~mask;
                final int menuButtons = 0x30; // Start + Select.
                if (gamepadConnected && pressed && event.getRepeatCount() == 0 &&
                    (mask & menuButtons) != 0 && (padButtons & menuButtons) == menuButtons) {
                    showPlayerMenu();
                    return true;
                }
                publishPad();
                return true;
            }
            if (key == KeyEvent.KEYCODE_BUTTON_L2 || key == KeyEvent.KEYCODE_BUTTON_R2) {
                if (key == KeyEvent.KEYCODE_BUTTON_L2) leftTrigger = pressed ? 1 : 0;
                else rightTrigger = pressed ? 1 : 0;
                publishPad();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean handleLibraryController(KeyEvent event) {
        if (home || settingsScreen) {
            Runnable action;
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_BUTTON_A:
                    View focus = getCurrentFocus();
                    if (focus instanceof android.widget.AdapterView<?>) {
                        android.widget.AdapterView<?> list = (android.widget.AdapterView<?>) focus;
                        int position = list.getSelectedItemPosition();
                        if (position == android.widget.AdapterView.INVALID_POSITION) return false;
                        action = () -> list.performItemClick(list.getSelectedView(), position,
                            list.getSelectedItemId());
                    } else if (focus != null && focus.isClickable()) {
                        action = focus::performClick;
                    } else return false;
                    break;
                case KeyEvent.KEYCODE_BUTTON_X:
                case KeyEvent.KEYCODE_BUTTON_START: action = this::showLibraryMenu; break;
                case KeyEvent.KEYCODE_BUTTON_Y:
                    action = () -> { if (!loadingGames) refreshLibrary(); };
                    break;
                case KeyEvent.KEYCODE_BUTTON_B:
                    if (homeGenre.isEmpty()) return false;
                    action = this::openHome;
                    break;
                default: return false;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                action.run();
            return true;
        }
        int count = games.length / GAME_FIELDS;
        int step;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_A:
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                    launchSelection();
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
                if (search.isEmpty()) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                    loadSection(ALL_GAMES);
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_START:
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                    showLibraryMenu();
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (!loadingGames && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                    refreshLibrary();
                return true;
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_BUTTON_R2:
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    int direction = event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_L2 ? -1 : 1;
                    loadSection((section + direction + SECTIONS.length) % SECTIONS.length);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1: step = -5; break;
            case KeyEvent.KEYCODE_BUTTON_R1: step = 5; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: step = -1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: step = 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: step = -libraryColumns(); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: step = libraryColumns(); break;
            default: return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && !loadingGames && count > 0)
            moveLibrarySelection(step);
        return true;
    }

    private void moveLibrarySelection(int step) {
        int current = 0;
        while (current < visiblePositions.length && visiblePositions[current] != selection) ++current;
        int position = Math.max(0, Math.min(visiblePositions.length - 1, current + step));
        grid.setSelection(position);
        selectGame(visiblePositions[position]);
    }

    private static float axis(MotionEvent event, int axis) {
        InputDevice device = event.getDevice();
        InputDevice.MotionRange range = device == null ? null : device.getMotionRange(axis, event.getSource());
        float value = event.getAxisValue(axis);
        return range == null || Math.abs(value) <= Math.max(range.getFlat(), 0.07f) ? 0 :
            Math.max(-1f, Math.min(1f, value));
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.getActionMasked() != MotionEvent.ACTION_MOVE)
            return super.onGenericMotionEvent(event);
        if (libraryVisible && !home && !settingsScreen && !(getCurrentFocus() instanceof EditText)) {
            float stick = axis(event, listLayout ? MotionEvent.AXIS_Y : MotionEvent.AXIS_X);
            float hat = event.getAxisValue(listLayout ? MotionEvent.AXIS_HAT_Y : MotionEvent.AXIS_HAT_X);
            float movement = Math.abs(hat) > Math.abs(stick) ? hat : stick;
            int direction = movement < -0.55f ? -1 : movement > 0.55f ? 1 : 0;
            if (direction != libraryDirection && direction != 0 && !loadingGames && games.length > 0)
                moveLibrarySelection(direction);
            libraryDirection = direction;
            return true;
        }
        if (player == null) return super.onGenericMotionEvent(event);
        if (isGamepad(event.getDevice()) && controllerDevice != event.getDeviceId()) {
            resetPad();
            controllerDevice = event.getDeviceId();
            refreshGamepads();
        }
        lx = axis(event, MotionEvent.AXIS_X);
        ly = -axis(event, MotionEvent.AXIS_Y);
        rx = axis(event, MotionEvent.AXIS_Z);
        ry = -axis(event, MotionEvent.AXIS_RZ);
        leftTrigger = Math.max(0, Math.max(axis(event, MotionEvent.AXIS_LTRIGGER), axis(event, MotionEvent.AXIS_BRAKE)));
        rightTrigger = Math.max(0, Math.max(axis(event, MotionEvent.AXIS_RTRIGGER), axis(event, MotionEvent.AXIS_GAS)));
        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        hatButtons = (hatX < -0.5f ? 4 : hatX > 0.5f ? 8 : 0) |
                     (hatY < -0.5f ? 1 : hatY > 0.5f ? 2 : 0);
        publishPad();
        return true;
    }

    private void updateRumble(int report) {
        InputDevice device = InputDevice.getDevice(controllerDevice);
        if (device == null) return;
        Vibrator motor = device.getVibrator();
        if (!motor.hasVibrator()) return;
        int strength = Math.max((report >> 8) & 0xff, report & 0xff);
        if (strength == 0) motor.cancel();
        else motor.vibrate(VibrationEffect.createOneShot(140,
            motor.hasAmplitudeControl() ? strength : VibrationEffect.DEFAULT_AMPLITUDE));
    }

    @Override public void onBackPressed() {
        if (player != null) showPlayerMenu();
        else if (authenticating) cancelSignIn();
        else if (libraryVisible && settingsScreen) openHome();
        else if (libraryVisible && inlineSearch) {
            inlineSearch = false;
            loadSection(ALL_GAMES);
        }
        else if (libraryVisible && !search.isEmpty()) loadSection(ALL_GAMES);
        else if (libraryVisible && (!home || !homeGenre.isEmpty())) openHome();
        else super.onBackPressed();
    }

    @Override protected void onPause() {
        if (player != null) resetPad();
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (inputManager != null) inputManager.unregisterInputDeviceListener(inputListener);
        ++operation;
        if (playerMenu != null) playerMenu.dismiss();
        if (player != null) stopPlaybackAsync(null);
        super.onDestroy();
    }
}
