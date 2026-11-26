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
import android.content.pm.PackageManager;
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
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

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
    private static final int WAITING_FOR_SERVER = 1;
    private static final int CONNECTING = 2;
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
    private volatile long artworkGeneration;
    private volatile boolean catalogPaused;
    private boolean catalogRefreshPending;
    private TextView libraryStatus;
    private TextView streamStatus;
    private LinearLayout launchPanel;
    private TextView launchStage;
    private TextView launchDetails;
    private TextView leaveLaunch;
    private ProgressBar launchProgress;
    private boolean launching;
    private LinearLayout metrics;
    private TextView[] metricValues;
    private TextView metricDetails;
    private View controls;
    private View playerMenuButton;
    private View showTouchButton;
    private boolean playerShortcutsVisible;
    private final Runnable hidePlayerShortcuts = () -> setPlayerShortcuts(false);
    private SurfaceView playerSurface;
    private AlertDialog playerMenu;
    private PlayerFraming playerFraming;
    private StreamProfile playerProfile;
    private android.net.wifi.WifiManager.WifiLock playbackWifiLock;
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
    private volatile boolean showMetrics;
    private Boolean touchControlsOverride;
    private volatile ScheduledFuture<?> playbackTicker;
    private PlaybackReport playbackReport;
    private String playerTitle;
    private String selectedGameKey;
    private final java.util.concurrent.ExecutorService pointerWorker =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    private long lastPointerMoveAt;
    private int activePointerId = -1;
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
    private native String[] nativeGameDetail(int index);
    private native String[] nativeGameDetailByKey(String key);
    private native boolean nativeSendKey(String key, boolean pressed);
    private native boolean nativeSendPointerMove(double xPercent, double yPercent);
    private native boolean nativeSendPointerButton(boolean pressed);
    private native void nativeStart(int index, Surface surface, boolean preferBrazilianPortuguese,
                                    int height, int fps, int bitrateMbps);
    private native void nativeStartByKey(String key, Surface surface, boolean preferBrazilianPortuguese,
                                         int height, int fps, int bitrateMbps);
    private native int nativeTick(int buttons, float lt, float rt, float lx, float ly, float rx, float ry);
    private native String[] nativeStatus();
    private native boolean nativeSetVideoSurface(Surface surface);
    private native void nativeStop();
    private native boolean nativeSetMicrophone(boolean enabled);
    private native boolean nativeMicrophoneActive();
    private volatile boolean microphoneEnabled;
    private long microphonePermissionOperation;

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
        favoritesScreen = false;
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

    private boolean favoritesScreen;
    private static final String FAVORITES_PREF = "favorite_games";

    private java.util.Set<String> favoriteKeys() {
        return new java.util.HashSet<>(getPreferences(MODE_PRIVATE)
            .getStringSet(FAVORITES_PREF, new java.util.HashSet<>()));
    }

    private boolean isFavorite(String key) {
        return key != null && !key.isEmpty() && favoriteKeys().contains(key);
    }

    private boolean toggleFavorite(String key) {
        if (key == null || key.isEmpty()) return false;
        java.util.Set<String> favs = favoriteKeys();
        boolean added;
        if (favs.contains(key)) {
            favs.remove(key);
            added = false;
        } else {
            favs.add(key);
            added = true;
        }
        getPreferences(MODE_PRIVATE).edit().putStringSet(FAVORITES_PREF, favs).apply();
        return added;
    }

    private void showFavorites() {
        if (loadingGames) return;
        inlineSearch = false;
        favoritesScreen = true;
        settingsScreen = false;
        String[] source = homeRows != null && homeRows.length > ALL_GAMES && homeRows[ALL_GAMES] != null
            ? homeRows[ALL_GAMES] : games;
        if (source == null || source.length == 0) {
            loadingGames = true;
            long token = ++operation;
            worker.execute(() -> {
                try {
                    String[] rows = nativeSection(ALL_GAMES);
                    post(token, () -> {
                        loadingGames = false;
                        if (homeRows != null && homeRows.length > ALL_GAMES) homeRows[ALL_GAMES] = rows;
                        renderFavoritesFrom(rows);
                    });
                } catch (Exception error) {
                    post(token, () -> {
                        loadingGames = false;
                        renderFavoritesFrom(new String[0]);
                    });
                }
            });
            return;
        }
        renderFavoritesFrom(source);
    }

    private void renderFavoritesFrom(String[] source) {
        java.util.Set<String> favs = favoriteKeys();
        java.util.List<String> list = new java.util.ArrayList<>();
        if (source != null) {
            for (int i = 0; i + GAME_FIELDS <= source.length; i += GAME_FIELDS) {
                if (favs.contains(source[i + GAME_KEY])) {
                    for (int j = 0; j < GAME_FIELDS; j++) list.add(source[i + j]);
                }
            }
        }
        search = "";
        showLibrary(list.toArray(new String[0]), true);
        if (list.isEmpty() && libraryStatus != null) {
            libraryStatus.setText("Nenhum jogo favoritado ainda. Abra um jogo e toque no ♥!");
        }
    }

    private void addBottomBar(LinearLayout page, boolean atHome) {
        LinearLayout bottom = new LinearLayout(this);
        bottom.setPadding(dp(4), dp(5), dp(4), dp(8));
        bottom.setBackgroundColor(0xff171222);
        bottom.addView(navButton("home", "Início", atHome && !settingsScreen && !favoritesScreen, this::openHome),
            new LinearLayout.LayoutParams(0, -2, 1));
        bottom.addView(navButton("search", "Pesquisar", false, this::showLibrarySearch),
            new LinearLayout.LayoutParams(0, -2, 1));
        bottom.addView(navButton("grid", "Biblioteca", !atHome && !settingsScreen && !favoritesScreen, () -> {
            if (loadingGames) return;
            inlineSearch = false;
            favoritesScreen = false;
            loadSection(ALL_GAMES);
        }), new LinearLayout.LayoutParams(0, -2, 1));
        bottom.addView(navButton("heart", "Favoritos", favoritesScreen, this::showFavorites),
            new LinearLayout.LayoutParams(0, -2, 1));
        bottom.addView(navButton("settings", "Ajustes", settingsScreen && !favoritesScreen, this::showSettings),
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
                case "heart":
                    path.reset();
                    path.moveTo(12, 20.2f);
                    path.cubicTo(12, 20.2f, 4, 14.8f, 4, 9.2f);
                    path.cubicTo(4, 6.2f, 6.4f, 4.2f, 8.9f, 4.2f);
                    path.cubicTo(10.5f, 4.2f, 11.5f, 5.1f, 12, 6.1f);
                    path.cubicTo(12.5f, 5.1f, 13.5f, 4.2f, 15.1f, 4.2f);
                    path.cubicTo(17.6f, 4.2f, 20, 6.2f, 20, 9.2f);
                    path.cubicTo(20, 14.8f, 12, 20.2f, 12, 20.2f);
                    canvas.drawPath(path, paint);
                    break;
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
            resolveHomeLaunch(source, key);
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
                        showGameDetailFromRows(rows, offset / GAME_FIELDS);
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
        showLibrary(library, false);
    }

    private void showLibrary(String[] library, boolean isFavorites) {
        home = false;
        settingsScreen = false;
        favoritesScreen = isFavorites;
        games = library == null ? new String[0] : library;
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
        favoritesScreen = false;
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
        StreamProfile active = StreamProfile.active(getPreferences(MODE_PRIVATE));
        addSetting(body, "play", "Perfis de transmissão", active.name + " · " + active.videoSummary(),
            this::showStreamProfiles);
        addSetting(body, "play", "Enquadramento padrão", "Usado pelos perfis prontos", this::showFramingSettings);
        addSetting(body, "action", "Idioma dos jogos", "Preferir português nas partidas", this::showGameLanguage);
        addSetting(body, "filter", "Catálogo", "Atualizar jogos ou ordenar a Biblioteca", this::showLibraryMenu);
        addSetting(body, "trophy", "Luna Premium", "Ver os jogos do plano Premium", () -> loadSection(PREMIUM_GAMES));
        scroll.addView(body);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        addBottomBar(page, false);
        enterLibraryView(scroll, 0, 12);
    }

    private PlayerFraming defaultFraming() {
        return PlayerFraming.fromPreference(getPreferences(MODE_PRIVATE).getString("player_framing", ""));
    }

    private void showStreamProfiles() {
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        StreamProfile active = StreamProfile.active(prefs);
        Dialog dialog = new Dialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = profilePanel(scroll, "Perfis de transmissão");
        TextView intro = text("O perfil ativo será usado ao tocar em Jogar.", 14, MUTED);
        intro.setPadding(0, 0, 0, dp(12));
        panel.addView(intro);

        for (StreamProfile profile : StreamProfile.all(prefs)) {
            boolean selected = profile.id.equals(active.id);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(chrome(selected ? 0xff3c2955 : LIBRARY_CARD, 16));
            LinearLayout.LayoutParams cardSize = new LinearLayout.LayoutParams(-1, -2);
            cardSize.bottomMargin = dp(8);
            panel.addView(card, cardSize);

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            row.setBackground(tapBackground(Color.TRANSPARENT, 16));
            row.setFocusable(true);
            row.setSelected(selected);
            row.setOnClickListener(v -> {
                profile.activate(prefs);
                dialog.dismiss();
                showSettings();
                showStreamProfiles();
            });
            card.addView(row);

            LinearLayout description = new LinearLayout(this);
            description.setOrientation(LinearLayout.VERTICAL);
            row.addView(description, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView name = text(profile.name, 16, Color.WHITE);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            description.addView(name);
            description.addView(text(profile.videoSummary(), 13, 0xffd7c9e8));
            if (selected) row.addView(text("Ativo", 13, LIBRARY_SELECTED));

            if (profile.custom() && selected) {
                LinearLayout actions = new LinearLayout(this);
                actions.setPadding(dp(8), 0, dp(8), dp(8));
                card.addView(actions);
                LinearLayout.LayoutParams editSize = new LinearLayout.LayoutParams(0, -2, 1f);
                editSize.setMarginEnd(dp(8));
                actions.addView(libraryAction("Editar", false, () -> {
                    dialog.dismiss();
                    editStreamProfile(profile, true);
                }), editSize);
                actions.addView(libraryAction("Excluir", false, () -> new AlertDialog.Builder(this)
                    .setTitle("Excluir " + profile.name + "?")
                    .setMessage("O perfil Equilibrado será ativado.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Excluir", (confirm, which) -> {
                        profile.remove(prefs);
                        dialog.dismiss();
                        showSettings();
                        showStreamProfiles();
                    }).show()), new LinearLayout.LayoutParams(0, -2, 1f));
            }
        }

        panel.addView(libraryAction("Criar perfil", true, () -> {
            dialog.dismiss();
            editStreamProfile(active, false);
        }));
        LinearLayout.LayoutParams close = new LinearLayout.LayoutParams(-1, -2);
        close.topMargin = dp(8);
        panel.addView(libraryAction("Fechar", false, dialog::dismiss), close);
        showLibraryDialog(dialog, scroll);
    }

    private LinearLayout profilePanel(ScrollView scroll, String title) {
        scroll.setBackground(chrome(0xff20172d, 24));
        scroll.setClipToOutline(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(16));
        scroll.addView(panel);
        TextView heading = heading(title);
        heading.setTextSize(23);
        heading.setPadding(0, 0, 0, dp(8));
        panel.addView(heading);
        return panel;
    }

    private Spinner profileChoice(LinearLayout panel, String label, String[] options, int selected) {
        TextView heading = text(label, 13, MUTED);
        heading.setPadding(0, dp(8), 0, dp(2));
        panel.addView(heading);
        Spinner choice = new Spinner(this, Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        choice.setAdapter(adapter);
        choice.setSelection(selected);
        choice.setContentDescription(label);
        choice.setBackgroundTintList(ColorStateList.valueOf(LIBRARY_SELECTED));
        choice.setPadding(dp(12), 0, dp(12), 0);
        panel.addView(choice, new LinearLayout.LayoutParams(-1, dp(46)));
        return choice;
    }

    private void editStreamProfile(StreamProfile source, boolean editing) {
        Dialog dialog = new Dialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = profilePanel(scroll, editing ? "Editar perfil" : "Novo perfil");
        EditText name = new EditText(this);
        name.setTextColor(Color.WHITE);
        name.setHintTextColor(MUTED);
        name.setHint("Nome do perfil");
        name.setSingleLine(true);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(32)});
        name.setText(editing ? source.name : "");
        name.setPadding(dp(12), 0, dp(12), 0);
        name.setBackground(chrome(LIBRARY_CARD, 12));
        panel.addView(name, new LinearLayout.LayoutParams(-1, dp(48)));

        Spinner resolution = profileChoice(panel, "Resolução solicitada",
            new String[]{"720p · 1280 × 720", "1080p · 1920 × 1080"}, source.height == 1080 ? 1 : 0);
        Spinner rate = profileChoice(panel, "Taxa de quadros solicitada",
            new String[]{"30 FPS", "60 FPS"}, source.fps == 60 ? 1 : 0);
        TextView bitrate = text("", 14, Color.WHITE);
        bitrate.setPadding(0, dp(12), 0, 0);
        panel.addView(bitrate);
        SeekBar cap = new SeekBar(this);
        cap.setContentDescription("Limite de bitrate em megabits por segundo");
        cap.setMax(StreamProfile.MAX_BITRATE - StreamProfile.MIN_BITRATE);
        cap.setProgress(source.bitrateMbps - StreamProfile.MIN_BITRATE);
        cap.setProgressTintList(ColorStateList.valueOf(LIBRARY_SELECTED));
        cap.setThumbTintList(ColorStateList.valueOf(LIBRARY_SELECTED));
        panel.addView(cap, new LinearLayout.LayoutParams(-1, dp(42)));
        bitrate.setText("Limite de bitrate · " + source.bitrateMbps + " Mb/s");
        cap.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int progress, boolean fromUser) {
                bitrate.setText("Limite de bitrate · " + (progress + StreamProfile.MIN_BITRATE) + " Mb/s");
            }
            @Override public void onStartTrackingTouch(SeekBar view) {}
            @Override public void onStopTrackingTouch(SeekBar view) {}
        });
        PlayerFraming[] modes = PlayerFraming.values();
        String[] framingLabels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) framingLabels[i] = modes[i].label;
        Spinner framing = profileChoice(panel, "Enquadramento", framingLabels,
            (source.custom() ? source.framing : defaultFraming()).ordinal());
        Switch metrics = new Switch(this);
        metrics.setText("Abrir com métricas visíveis");
        metrics.setTextColor(Color.WHITE);
        metrics.setChecked(source.metrics);
        panel.addView(metrics, new LinearLayout.LayoutParams(-1, dp(52)));
        panel.addView(libraryAction("Salvar e ativar", true, () -> {
            String title = name.getText().toString().trim();
            if (title.isEmpty()) {
                name.setError("Dê um nome ao perfil.");
                name.requestFocus();
                return;
            }
            new StreamProfile(editing ? source.id : StreamProfile.newId(), title,
                resolution.getSelectedItemPosition() == 1 ? 1080 : 720,
                rate.getSelectedItemPosition() == 0 ? 30 : 60,
                cap.getProgress() + StreamProfile.MIN_BITRATE,
                modes[framing.getSelectedItemPosition()], metrics.isChecked())
                .saveAndActivate(getPreferences(MODE_PRIVATE));
            dialog.dismiss();
            showSettings();
            showStreamProfiles();
        }));
        LinearLayout.LayoutParams cancel = new LinearLayout.LayoutParams(-1, -2);
        cancel.topMargin = dp(8);
        panel.addView(libraryAction("Cancelar", false, () -> {
            dialog.dismiss();
            showStreamProfiles();
        }), cancel);
        panel.setFocusableInTouchMode(true);
        panel.requestFocus();
        dialog.setOnCancelListener(ignored -> showStreamProfiles());
        showLibraryDialog(dialog, scroll);
    }

    private void showFramingSettings() {
        PlayerFraming[] modes = PlayerFraming.values();
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; ++i) labels[i] = modes[i].label;
        PlayerFraming current = defaultFraming();
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
        String label = favoritesScreen ? "Favoritos" : (search.isEmpty() ? SECTIONS[section] : "Resultados");
        String status = label + " · " + (count == 0 ? "Nenhum jogo encontrado" :
            count + (count == 1 ? " jogo" : " jogos"));
        if (!favoritesScreen && search.isEmpty() && section == PREMIUM_GAMES && !libraryInfo[3].isEmpty())
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
        if (!libraryVisible || home || settingsScreen || loadingGames || games == null || games.length == 0) return;
        if (selection >= 0 && selection * GAME_FIELDS + GAME_FIELDS <= games.length) {
            showGameDetail(selection);
        }
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
        if (!canExploreCatalog(generation) || !libraryVisible || loadingGames || settingsScreen || favoritesScreen) return;
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
        if (url.isEmpty() || cached != null || player != null) return;
        long generation = artworkGeneration;
        artwork.execute(() -> {
            if (destroyed || artworkGeneration != generation) return;
            Bitmap ready = covers.get(url);
            if (ready != null) {
                ui.post(() -> {
                    if (!destroyed && artworkGeneration == generation && url.equals(image.getTag()))
                        revealArtwork(image, ready);
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
                    if (destroyed || artworkGeneration != generation) return;
                    Bitmap decoded = BitmapFactory.decodeStream(input);
                    if (decoded == null) return;
                    covers.put(url, decoded);
                    ui.post(() -> {
                        if (!destroyed && artworkGeneration == generation && url.equals(image.getTag()))
                            revealArtwork(image, decoded);
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

    private View gameDetailOverlay;
    private boolean favoritesDirty;

    private void closeGameDetail() {
        android.view.ViewGroup host = (android.view.ViewGroup) findViewById(android.R.id.content);
        if (gameDetailOverlay == null || host == null) return;
        View overlay = gameDetailOverlay;
        gameDetailOverlay = null;
        boolean refreshFavs = favoritesDirty;
        favoritesDirty = false;
        overlay.animate().alpha(0f).translationY(dp(36)).setDuration(180)
            .withEndAction(() -> {
                host.removeView(overlay);
                if (refreshFavs && favoritesScreen && player == null) showFavorites();
            }).start();
    }

    private void showGameDetail(int index) {
        showGameDetailFromRows(games, index);
    }

    private void showGameDetailFromRows(String[] rowSource, int index) {
        if (rowSource == null || index < 0 || index * GAME_FIELDS + GAME_FIELDS > rowSource.length) return;
        closeGameDetail();
        int base = index * GAME_FIELDS;
        String initialTitle = rowSource[base];
        String initialPortrait = rowSource[base + 2];
        String initialLandscape = rowSource[base + 3];
        final String gameKey = rowSource[base + GAME_KEY];

        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xff0b0a10);
        overlay.setClickable(true);
        gameDetailOverlay = overlay;

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(12), dp(16), dp(28));

        // Top bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(0, dp(4), 0, dp(14));
        TextView backBtn = text("←", 20, Color.WHITE);
        backBtn.setPadding(dp(4), dp(4), dp(14), dp(4));
        backBtn.setOnClickListener(v -> closeGameDetail());
        TextView brand = text("LunaNX", 18, Color.WHITE);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(0, -2, 1f);
        boolean initialFav = isFavorite(gameKey);
        TextView favTop = text(initialFav ? "♥" : "♡", 20, initialFav ? 0xfff43f5e : 0xffe2e8f0);
        favTop.setPadding(dp(10), dp(4), dp(10), dp(4));
        TextView moreTop = text("⋮", 20, 0xffe2e8f0);
        moreTop.setPadding(dp(8), dp(4), dp(4), dp(4));
        moreTop.setOnClickListener(v -> showFramingSettings());
        topBar.addView(backBtn);
        topBar.addView(brand, brandLp);
        topBar.addView(favTop);
        topBar.addView(moreTop);
        body.addView(topBar);

        // Banner horizontal
        FrameLayout bannerWrap = new FrameLayout(this);
        ImageView banner = coverView(initialLandscape != null && !initialLandscape.isEmpty() ? initialLandscape : initialPortrait, 16);
        bannerWrap.addView(banner, new FrameLayout.LayoutParams(-1, dp(170)));
        TextView playCircle = text("▶", 18, Color.WHITE);
        playCircle.setGravity(Gravity.CENTER);
        playCircle.setBackground(rounded(0x99000000, 100));
        bannerWrap.addView(playCircle, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        bannerWrap.setOnClickListener(v -> {
            if (player != null) return;
            closeGameDetail();
            showPlayerByKey(gameKey, initialTitle);
        });
        body.addView(bannerWrap, new LinearLayout.LayoutParams(-1, dp(170)));

        // Capa + Título + Publisher + Badge
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(-1, -2);
        hLp.topMargin = dp(16);
        ImageView poster = coverView(initialPortrait, 14);
        headerRow.addView(poster, new LinearLayout.LayoutParams(dp(92), dp(122)));

        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        infoCol.setPadding(dp(14), 0, 0, 0);
        TextView titleView = text(initialTitle, 20, Color.WHITE);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView pubSub = text("Carregando detalhes…", 13, 0xff94a3b8);
        pubSub.setPadding(0, dp(4), 0, dp(2));
        TextView genreSub = text("", 12, 0xff94a3b8);
        TextView badge = text("👑 Premium", 11, 0xffe9d5ff);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setPadding(dp(10), dp(4), dp(10), dp(4));
        badge.setBackground(rounded(0xff3b235a, 100));
        badge.setVisibility(View.GONE);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-2, -2);
        bLp.topMargin = dp(8);
        infoCol.addView(titleView);
        infoCol.addView(pubSub);
        infoCol.addView(genreSub);
        infoCol.addView(badge, bLp);
        headerRow.addView(infoCol, new LinearLayout.LayoutParams(0, -2, 1f));
        body.addView(headerRow, hLp);

        // Barra de ação: ▶ Iniciar + ☁ + ♡
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(-1, -2);
        aLp.topMargin = dp(16);

        TextView startBtn = text("▶  Iniciar", 15, 0xff161124);
        startBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        startBtn.setGravity(Gravity.CENTER);
        startBtn.setBackground(rounded(0xffc4b5fd, 100));
        startBtn.setOnClickListener(v -> {
            if (player != null) return;
            animateTap(startBtn);
            closeGameDetail();
            showPlayerByKey(gameKey, initialTitle);
        });
        actionRow.addView(startBtn, new LinearLayout.LayoutParams(0, dp(46), 1f));

        FrameLayout cloudBtn = new FrameLayout(this);
        cloudBtn.setBackground(rounded(0xff1e1b29, 100));
        cloudBtn.addView(new HudIconView(this, "cloud", Color.WHITE, dp(1.6f)), new FrameLayout.LayoutParams(-1, -1));
        cloudBtn.setOnClickListener(v -> {
            animateTap(cloudBtn);
            showFramingSettings();
        });
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(dp(46), dp(46));
        cLp.setMarginStart(dp(10));
        actionRow.addView(cloudBtn, cLp);

        TextView heartBtn = text(initialFav ? "♥" : "♡", 17, initialFav ? 0xfff43f5e : Color.WHITE);
        heartBtn.setGravity(Gravity.CENTER);
        heartBtn.setBackground(rounded(0xff1e1b29, 100));
        Runnable syncFav = () -> {
            boolean nowFav = toggleFavorite(gameKey);
            favTop.setText(nowFav ? "♥" : "♡");
            favTop.setTextColor(nowFav ? 0xfff43f5e : 0xffe2e8f0);
            heartBtn.setText(nowFav ? "♥" : "♡");
            heartBtn.setTextColor(nowFav ? 0xfff43f5e : Color.WHITE);
            if (favoritesScreen) favoritesDirty = true;
        };
        favTop.setOnClickListener(v -> { animateTap(favTop); syncFav.run(); });
        heartBtn.setOnClickListener(v -> { animateTap(heartBtn); syncFav.run(); });
        LinearLayout.LayoutParams heartLp = new LinearLayout.LayoutParams(dp(46), dp(46));
        heartLp.setMarginStart(dp(10));
        actionRow.addView(heartBtn, heartLp);
        body.addView(actionRow, aLp);

        // Sinopse
        TextView synopsis = text("", 13, 0xffcbd5e1);
        synopsis.setPadding(0, dp(16), 0, dp(14));
        synopsis.setLineSpacing(dp(3), 1f);
        body.addView(synopsis);

        // Card de Metadados
        LinearLayout metaCard = new LinearLayout(this);
        metaCard.setOrientation(LinearLayout.VERTICAL);
        metaCard.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable metaBg = rounded(0xff15121e, 16);
        metaBg.setStroke(dp(1), 0x1affffff);
        metaCard.setBackground(metaBg);

        LinearLayout pubDevRow = new LinearLayout(this);
        pubDevRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout pubCol = new LinearLayout(this);
        pubCol.setOrientation(LinearLayout.VERTICAL);
        pubCol.addView(text("Publicadora", 11, 0xff94a3b8));
        TextView pubVal = text("—", 12, Color.WHITE);
        pubCol.addView(pubVal);
        LinearLayout devCol = new LinearLayout(this);
        devCol.setOrientation(LinearLayout.VERTICAL);
        devCol.addView(text("Desenvolvedora", 11, 0xff94a3b8));
        TextView devVal = text("—", 12, Color.WHITE);
        devCol.addView(devVal);
        pubDevRow.addView(pubCol, new LinearLayout.LayoutParams(0, -2, 1f));
        pubDevRow.addView(devCol, new LinearLayout.LayoutParams(0, -2, 1f));
        metaCard.addView(pubDevRow);

        TextView genresTitle = text("Gêneros", 11, 0xff94a3b8);
        genresTitle.setPadding(0, dp(12), 0, dp(6));
        metaCard.addView(genresTitle);
        LinearLayout chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        metaCard.addView(chipsRow);

        LinearLayout ratingRow = new LinearLayout(this);
        ratingRow.setOrientation(LinearLayout.HORIZONTAL);
        ratingRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
        rLp.topMargin = dp(12);
        TextView ratingBadge = text("L", 13, Color.WHITE);
        ratingBadge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        ratingBadge.setGravity(Gravity.CENTER);
        ratingBadge.setBackground(rounded(0xffeab308, 6));
        ratingRow.addView(ratingBadge, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout ratingTextCol = new LinearLayout(this);
        ratingTextCol.setOrientation(LinearLayout.VERTICAL);
        ratingTextCol.setPadding(dp(10), 0, 0, 0);
        ratingTextCol.addView(text("Classificação indicativa", 12, Color.WHITE));
        TextView ratingDesc = text("Consultando classificação…", 11, 0xff94a3b8);
        ratingTextCol.addView(ratingDesc);
        ratingRow.addView(ratingTextCol);
        metaCard.addView(ratingRow, rLp);

        body.addView(metaCard);
        scroll.addView(body);
        overlay.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        overlay.setAlpha(0f);
        overlay.setTranslationY(dp(36));
        ((android.view.ViewGroup) findViewById(android.R.id.content)).addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.animate().alpha(1f).translationY(0f).setDuration(240)
            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();

        worker.execute(() -> {
            String[] d = null;
            try {
                d = nativeGameDetailByKey(gameKey);
            } catch (Exception ignored) {
            }
            final String[] detail = d;
            runOnUiThread(() -> {
                if (destroyed || gameDetailOverlay != overlay) return;
                if (detail == null || detail.length < 10) {
                    pubSub.setText("Amazon Luna");
                    synopsis.setText("Sem sinopse disponível para este título.");
                    ratingDesc.setText("Livre");
                    return;
                }
                if (!detail[0].isEmpty()) titleView.setText(detail[0]);
                pubSub.setText(detail[1].isEmpty() ? "Amazon Luna" : detail[1]);
                genreSub.setText(detail[5]);
                if ("1".equals(detail[9]) || !detail[8].isEmpty()) {
                    badge.setText("Premium" + (detail[8].isEmpty() ? "" : " · " + detail[8]));
                    badge.setVisibility(View.VISIBLE);
                }
                synopsis.setText(detail[3].isEmpty() ? "Sem sinopse disponível para este título." : detail[3]);
                pubVal.setText(detail[1].isEmpty() ? "—" : detail[1]);
                devVal.setText(detail[2].isEmpty() ? "—" : detail[2]);
                chipsRow.removeAllViews();
                for (String g : detail[5].split(",")) {
                    String trimmed = g.trim();
                    if (trimmed.isEmpty() || chipsRow.getChildCount() >= 4) continue;
                    TextView chip = text(trimmed, 11, 0xffe2e8f0);
                    chip.setPadding(dp(10), dp(4), dp(10), dp(4));
                    GradientDrawable cBg = rounded(0xff1e1b29, 100);
                    cBg.setStroke(dp(1), 0x22ffffff);
                    chip.setBackground(cBg);
                    LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(-2, -2);
                    chLp.setMarginEnd(dp(6));
                    chipsRow.addView(chip, chLp);
                }
                String age = detail[4].isEmpty() ? "Livre" : detail[4];
                ratingBadge.setText(age.length() > 3 ? age.substring(0, 2) : age);
                ratingDesc.setText(age);
                if (!detail[7].isEmpty()) bindCover(banner, detail[7]);
                if (!detail[6].isEmpty()) bindCover(poster, detail[6]);
            });
        });
    }

    private void showPlayer(int index) {
        if (games == null || index < 0 || index * GAME_FIELDS + GAME_FIELDS > games.length) return;
        selectedGame = index;
        showPlayerByKey(games[index * GAME_FIELDS + GAME_KEY], games[index * GAME_FIELDS]);
    }

    private void showPlayerByKey(String key, String title) {
        if (player != null) return;
        libraryVisible = false;
        catalogPaused = true;
        ++catalogGeneration;
        ++artworkGeneration;
        selectedGameKey = key;
        playerTitle = title;
        playerProfile = StreamProfile.active(getPreferences(MODE_PRIVATE));
        playbackReport = new PlaybackReport(SystemClock.elapsedRealtime(),
            playerProfile.name + " · " + playerProfile.videoSummary());
        launching = true;
        microphoneEnabled = false;
        showMetrics = playerProfile.metrics;
        touchControlsOverride = null;
        playerShortcutsVisible = false;
        videoSurfaceError = null;
        playerFraming = playerProfile.custom() ? playerProfile.framing : defaultFraming();
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
            gameDetailOverlay = null;
            favoritesDirty = false;
            if (settingsScreen) showSettings();
            else if (favoritesScreen) showFavorites();
            else if (home && homeRows != null) showHome(homeRows, true);
            else showLibrary(games, false);
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
        surface.getHolder().setFixedSize(playerProfile.width(), playerProfile.height);
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
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                if (player != current || launching) return true;
                if (activeHudCard != null) {
                    closeActiveHudCard();
                } else if (!cleanScreenMode) {
                    revealPlayerShortcuts();
                }
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent event) {
                if (player != current || launching) return true;
                if (cleanScreenMode) toggleCleanScreen(current);
                else toggleSettingsCard(current);
                return true;
            }
        });
        current.setOnTouchListener((view, event) -> {
            if (player == current && !launching && !cleanScreenMode && directTouchEnabled && !gamepadConnected &&
                activeHudCard == null && !(playerShortcutsVisible && event.getY() < dp(68))) {
                dispatchDirectTouch(event);
            }
            return gestures.onTouchEvent(event);
        });
        streamStatus = text("", 16, Color.WHITE);
        streamStatus.setVisibility(View.GONE);
        streamStatus.setGravity(Gravity.CENTER);
        streamStatus.setPadding(dp(16), dp(8), dp(16), dp(8));
        streamStatus.setBackground(rounded(0xd91c2030, 12));
        current.addView(streamStatus, floating(-2, -2, Gravity.CENTER, 16));

        controls = touchOverlay();
        current.addView(controls, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout backBtn = playerCircleVectorIcon("back", false, "Sair do jogo", () -> leavePlayer(false));
        playerMenuButton = backBtn;
        current.addView(backBtn, floating(dp(42), dp(42), Gravity.TOP | Gravity.START, 14));

        LinearLayout pillWrap = new LinearLayout(this);
        pillWrap.setOrientation(LinearLayout.HORIZONTAL);
        pillWrap.setGravity(Gravity.CENTER_VERTICAL);
        pillWrap.setPadding(dp(12), dp(6), dp(14), dp(6));
        GradientDrawable pillBg = rounded(0x8814161a, 100);
        pillBg.setStroke(dp(1), 0x33ffffff);
        pillWrap.setBackground(pillBg);
        HudIconView cloudIcon = new HudIconView(this, "cloud", 0xffeef2f6, dp(1.5f));
        pillWrap.addView(cloudIcon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        hudPill = text("Cloud Gaming  •  ● Jogando", 13, 0xffeef2f6);
        hudPill.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        hudPill.setPadding(dp(8), 0, 0, 0);
        hudPill.setMaxWidth(dp(240));
        hudPill.setSingleLine(true);
        hudPill.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pillWrap.addView(hudPill);
        hudPillWrap = pillWrap;
        current.addView(pillWrap, floating(-2, dp(38), Gravity.TOP | Gravity.CENTER_HORIZONTAL, 16));

        LinearLayout rightHud = new LinearLayout(this);
        rightHud.setOrientation(LinearLayout.HORIZONTAL);
        rightHud.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout padWrap = playerCircleVectorIcon("gamepad", gamepadConnected || touchControlsEnabled(), "Mostrar ou ocultar controles", () -> {
            if (gamepadConnected) {
                Toast.makeText(this, "Controle físico conectado: controles virtuais desativados.", Toast.LENGTH_SHORT).show();
            } else {
                touchControlsOverride = !touchControlsEnabled();
                updatePlayerOverlays();
                syncHudIndicators();
            }
            revealPlayerShortcuts();
        });
        padHudIcon = (HudIconView) padWrap.getChildAt(0);
        showTouchButton = padWrap;

        FrameLayout touchWrap = playerCircleVectorIcon("pointer", directTouchEnabled && !gamepadConnected, "Ativar ou desativar toque tátil", () -> {
            if (gamepadConnected) {
                Toast.makeText(this, "Toque tátil fica desativado com controle físico.", Toast.LENGTH_SHORT).show();
            } else {
                directTouchEnabled = !directTouchEnabled;
                syncHudIndicators();
            }
            revealPlayerShortcuts();
        });
        touchHudIcon = (HudIconView) touchWrap.getChildAt(0);

        FrameLayout keyBtn = playerCircleVectorIcon("keyboard", false, "Abrir ou fechar teclado", () -> toggleKeyboardBar(current));
        keyHudIcon = (HudIconView) keyBtn.getChildAt(0);
        FrameLayout castBtn = playerCircleVectorIcon("screen", false, "Tela e Enquadramento", () -> toggleFramingCard(current));
        FrameLayout gearBtn = playerCircleVectorIcon("gear", false, "Ajustes do player", () -> toggleSettingsCard(current));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        iconLp.setMarginStart(dp(8));
        rightHud.addView(padWrap, new FrameLayout.LayoutParams(dp(42), dp(42)));
        rightHud.addView(touchWrap, iconLp);
        rightHud.addView(keyBtn, iconLp);
        rightHud.addView(castBtn, iconLp);
        rightHud.addView(gearBtn, iconLp);
        hudRightBar = rightHud;
        current.addView(rightHud, floating(-2, dp(42), Gravity.TOP | Gravity.END, 14));

        metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.VERTICAL);
        metrics.setPadding(dp(4), dp(3), dp(4), dp(3));
        metrics.setBackground(chrome(0xee20172d, 14));
        LinearLayout metricRow = new LinearLayout(this);
        metricValues = new TextView[3];
        String[] labels = {"FPS vídeo", "Mb/s", "Variação RTP"};
        for (int i = 0; i < labels.length; i++) {
            LinearLayout cell = statCell(metricRow, labels[i]);
            metricValues[i] = statValue(cell, "—");
        }
        metrics.addView(metricRow);
        metricDetails = text("", 12, 0xffe0d4ef);
        metricDetails.setPadding(dp(8), dp(4), dp(8), dp(8));
        metricDetails.setVisibility(View.GONE);
        metrics.addView(metricDetails);
        metrics.setFocusable(true);
        metrics.setContentDescription("Métricas da transmissão. Toque para alternar os detalhes.");
        metrics.setOnClickListener(view -> metricDetails.setVisibility(
            metricDetails.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        FrameLayout.LayoutParams metricsLp = floating(dp(282), -2, Gravity.TOP | Gravity.END, 14);
        metricsLp.topMargin = dp(64);
        current.addView(metrics, metricsLp);

        addLaunchPanel(current);
        updatePlayerOverlays();
    }

    private void addLaunchPanel(FrameLayout current) {
        launchPanel = new LinearLayout(this);
        launchPanel.setOrientation(LinearLayout.VERTICAL);
        launchPanel.setPadding(dp(20), dp(16), dp(20), dp(16));
        launchPanel.setBackground(chrome(0xff241930, 22));
        launchPanel.addView(text(playerTitle + " · " + playerProfile.name, 14, 0xffd7c4ec));

        launchStage = heading("Preparando a sessão");
        launchStage.setTextSize(24);
        launchStage.setPadding(0, dp(6), 0, dp(2));
        launchPanel.addView(launchStage);
        launchDetails = text("Solicitando uma sessão ao Luna…", 14, 0xffd7c4ec);
        launchPanel.addView(launchDetails);

        launchProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        launchProgress.setIndeterminate(true);
        launchProgress.setIndeterminateTintList(ColorStateList.valueOf(LIBRARY_SELECTED));
        LinearLayout.LayoutParams progress = new LinearLayout.LayoutParams(-1, dp(12));
        progress.topMargin = dp(12);
        progress.bottomMargin = dp(12);
        launchPanel.addView(launchProgress, progress);
        leaveLaunch = libraryAction("Cancelar espera", false, () -> leavePlayer(false));
        launchPanel.addView(leaveLaunch);

        int width = Math.max(1, Math.min(400, getResources().getConfiguration().screenWidthDp - 32));
        current.addView(launchPanel, floating(dp(width), -2, Gravity.CENTER, 16));
    }

    private void showLaunchProgress(int phase, String message, boolean queued, int position, int waitSeconds) {
        String stage;
        String details;
        if (phase >= ENDED) {
            stage = "Não foi possível iniciar";
            details = message;
        } else if (phase == WAITING_FOR_SERVER && queued) {
            stage = position > 0 ? "Posição " + position + " na fila" : "Aguardando na fila";
            if (waitSeconds < 0) {
                details = "Aguardando uma máquina disponível no Luna.";
            } else if (waitSeconds < 60) {
                details = "Espera estimada de menos de 1 minuto.";
            } else {
                long minutes = (waitSeconds + 59L) / 60;
                details = "Espera estimada de cerca de " + minutes + (minutes == 1 ? " minuto." : " minutos.");
            }
        } else if (phase == CONNECTING) {
            stage = "Conectando ao jogo";
            details = "Aguardando os primeiros quadros do vídeo…";
        } else {
            stage = phase == WAITING_FOR_SERVER ? "Preparando a máquina" : "Preparando a sessão";
            details = message;
        }
        if (!android.text.TextUtils.equals(launchStage.getText(), stage)) launchStage.setText(stage);
        if (!android.text.TextUtils.equals(launchDetails.getText(), details)) launchDetails.setText(details);
        launchProgress.setVisibility(phase < ENDED ? View.VISIBLE : View.INVISIBLE);
        String action = phase < ENDED ? "Cancelar espera" : "Voltar à biblioteca";
        if (!android.text.TextUtils.equals(leaveLaunch.getText(), action)) leaveLaunch.setText(action);
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

    private View activeHudCard;
    private View hudPillWrap;
    private View hudRightBar;
    private int activeSettingsTab;
    private boolean cleanScreenMode;
    private boolean directTouchEnabled = true;
    private TextView hudPill;
    private HudIconView padHudIcon;
    private HudIconView touchHudIcon;
    private HudIconView keyHudIcon;

    private void syncHudIndicators() {
        if (padHudIcon != null) padHudIcon.setGreenDot(gamepadConnected || touchControlsEnabled());
        if (touchHudIcon != null) touchHudIcon.setGreenDot(directTouchEnabled && !gamepadConnected);
        if (keyHudIcon != null) keyHudIcon.setGreenDot(activeHudCard != null && "keyboard".equals(activeHudCard.getTag()));
    }

    private void dispatchDirectTouch(MotionEvent event) {
        if (player == null || playerSurface == null) return;
        int w = playerSurface.getWidth();
        int h = playerSurface.getHeight();
        if (w <= 0 || h <= 0) return;
        int action = event.getActionMasked();
        int ptrIdx = event.getActionIndex();
        int ptrId = event.getPointerId(ptrIdx);
        if (action == MotionEvent.ACTION_DOWN) {
            activePointerId = ptrId;
        } else if (action == MotionEvent.ACTION_MOVE) {
            ptrIdx = activePointerId == -1 ? 0 : event.findPointerIndex(activePointerId);
            if (ptrIdx < 0) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastPointerMoveAt < 16) return;
            lastPointerMoveAt = now;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            activePointerId = -1;
        } else if (action == MotionEvent.ACTION_POINTER_UP && ptrId == activePointerId) {
            activePointerId = -1;
            action = MotionEvent.ACTION_UP;
        } else {
            return;
        }
        float sx = playerSurface.getScaleX() <= 0 ? 1f : playerSurface.getScaleX();
        float sy = playerSurface.getScaleY() <= 0 ? 1f : playerSurface.getScaleY();
        float vw = w * sx;
        float vh = h * sy;
        float left = (player.getWidth() - vw) / 2f;
        float top = (player.getHeight() - vh) / 2f;
        final double xPct = Math.max(0.0, Math.min(100.0, (event.getX(ptrIdx) - left) * 100.0 / vw));
        final double yPct = Math.max(0.0, Math.min(100.0, (event.getY(ptrIdx) - top) * 100.0 / vh));
        final int finalAction = action;
        pointerWorker.execute(() -> {
            if (finalAction == MotionEvent.ACTION_DOWN) {
                nativeSendPointerMove(xPct, yPct);
                nativeSendPointerButton(true);
            } else if (finalAction == MotionEvent.ACTION_MOVE) {
                nativeSendPointerMove(xPct, yPct);
            } else if (finalAction == MotionEvent.ACTION_UP || finalAction == MotionEvent.ACTION_CANCEL) {
                nativeSendPointerMove(xPct, yPct);
                nativeSendPointerButton(false);
            }
        });
    }

    private void animateTap(View v) {
        v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).withEndAction(() ->
            v.animate().scaleX(1f).scaleY(1f).setDuration(140)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start()
        ).start();
    }

    private void closeActiveHudCard() {
        if (activeHudCard == null) return;
        View card = activeHudCard;
        activeHudCard = null;
        if ("keyboard".equals(card.getTag())) {
            View focused = card.findFocus();
            View tokenTarget = focused != null ? focused : card;
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(tokenTarget.getWindowToken(), 0);
            if (focused != null) focused.clearFocus();
            card.clearFocus();
            if (player != null) enterPlayerFullscreen();
        }
        syncHudIndicators();
        if (player == null) {
            if (card.getParent() instanceof android.view.ViewGroup)
                ((android.view.ViewGroup) card.getParent()).removeView(card);
            return;
        }
        if (!cleanScreenMode) revealPlayerShortcuts();
        final FrameLayout host = player;
        card.animate().alpha(0f).translationY(-dp(12)).scaleX(0.95f).scaleY(0.95f)
            .setDuration(160).withEndAction(() -> {
                if (card.getParent() == host) host.removeView(card);
            }).start();
    }

    private void sendKeyTap(String key) {
        if (key == null || key.isEmpty()) return;
        worker.execute(() -> {
            nativeSendKey(key, true);
            nativeSendKey(key, false);
        });
    }

    private void toggleKeyboardBar(FrameLayout host) {
        if (activeHudCard != null && "keyboard".equals(activeHudCard.getTag())) {
            closeActiveHudCard();
            return;
        }
        LinearLayout bar = new LinearLayout(this);
        bar.setTag("keyboard");
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable bg = rounded(0xeb14161a, 18);
        bg.setStroke(dp(1), 0x28ffffff);
        bar.setBackground(bg);

        android.widget.EditText input = new android.widget.EditText(this) {
            @Override public boolean onKeyPreIme(int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    closeActiveHudCard();
                    return true;
                }
                return super.onKeyPreIme(keyCode, event);
            }
        };
        input.setHint("Digite para enviar ao jogo…");
        input.setHintTextColor(0xff94a3b8);
        input.setTextColor(Color.WHITE);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        final String[] prev = {""};
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                String cur = s.toString();
                if (cur.length() > prev[0].length()) {
                    String added = cur.substring(prev[0].length());
                    for (int i = 0; i < added.length(); i++) {
                        sendKeyTap(String.valueOf(added.charAt(i)));
                    }
                } else if (cur.length() < prev[0].length()) {
                    int diff = prev[0].length() - cur.length();
                    for (int i = 0; i < diff; i++) sendKeyTap("Backspace");
                }
                prev[0] = cur;
            }
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            sendKeyTap("Enter");
            input.setText("");
            prev[0] = "";
            return true;
        });
        bar.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView enterBtn = text("Enter", 12, Color.WHITE);
        enterBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        enterBtn.setPadding(dp(10), dp(6), dp(10), dp(6));
        enterBtn.setBackground(rounded(0xff2563eb, 10));
        enterBtn.setOnClickListener(v -> {
            animateTap(enterBtn);
            sendKeyTap("Enter");
        });
        bar.addView(enterBtn);

        TextView closeBtn = text("✕", 14, 0xff94a3b8);
        closeBtn.setPadding(dp(10), dp(6), dp(4), dp(6));
        closeBtn.setOnClickListener(v -> {
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            closeActiveHudCard();
        });
        bar.addView(closeBtn);

        showHudCard(host, bar, 340);
        input.requestFocus();
        input.post(() -> {
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void showHudCard(FrameLayout host, View card, int widthDp) {
        closeActiveHudCard();
        activeHudCard = card;
        syncHudIndicators();
        card.setAlpha(0f);
        card.setTranslationY(-dp(14));
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        FrameLayout.LayoutParams lp = floating(dp(widthDp), -2, Gravity.TOP | Gravity.END, 14);
        lp.topMargin = dp(64);
        host.addView(card, lp);
        card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(220).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    private void toggleCleanScreen(FrameLayout host) {
        cleanScreenMode = !cleanScreenMode;
        if (cleanScreenMode) resetTouchPad();
        float target = cleanScreenMode ? 0f : 1f;
        if (controls != null) {
            if (!cleanScreenMode && touchControlsEnabled()) controls.setVisibility(View.VISIBLE);
            controls.animate().alpha(target).setDuration(200)
                .withEndAction(() -> { if (cleanScreenMode && controls != null) controls.setVisibility(View.GONE); }).start();
        }
        View[] hudViews = {playerMenuButton, hudPillWrap, hudRightBar};
        for (View hv : hudViews) {
            if (hv == null) continue;
            if (!cleanScreenMode) hv.setVisibility(View.VISIBLE);
            hv.animate().alpha(target).setDuration(200)
                .withEndAction(() -> { if (cleanScreenMode) hv.setVisibility(View.GONE); }).start();
        }
        closeActiveHudCard();
        if (!cleanScreenMode) revealPlayerShortcuts();
    }

    private void toggleFramingCard(FrameLayout host) {
        if (activeHudCard != null && "framing".equals(activeHudCard.getTag())) {
            closeActiveHudCard();
            return;
        }
        ScrollView wrap = new ScrollView(this);
        wrap.setTag("framing");
        GradientDrawable bg = rounded(0xeb14161a, 22);
        bg.setStroke(dp(1), 0x28ffffff);
        wrap.setBackground(bg);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(10), dp(16), dp(16));
        wrap.addView(card, new FrameLayout.LayoutParams(-1, -2));

        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            card.removeAllViews();
            View handle = new View(this);
            handle.setBackground(rounded(0x44ffffff, 100));
            LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(dp(34), dp(4));
            hLp.gravity = Gravity.CENTER_HORIZONTAL;
            hLp.bottomMargin = dp(10);
            card.addView(handle, hLp);

            TextView title = text("Tela e Enquadramento", 15, Color.WHITE);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            card.addView(title);

            TextView sub = text("Modo de enquadramento", 12, 0xff9aa0a6);
            sub.setPadding(0, dp(12), 0, dp(6));
            card.addView(sub);

            for (final PlayerFraming m : PlayerFraming.values()) {
                boolean selected = playerFraming == m;
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                if (selected) row.setBackground(rounded(0x26ffffff, 12));
                TextView lbl = text(m.label, 13, selected ? Color.WHITE : 0xffcfd4dc);
                row.addView(lbl, new LinearLayout.LayoutParams(0, -2, 1f));
                if (selected) {
                    TextView chk = text("✓", 14, 0xff22c55e);
                    chk.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    row.addView(chk);
                }
                row.setOnClickListener(v -> {
                    playerFraming = m;
                    if (!playerProfile.custom())
                        getPreferences(MODE_PRIVATE).edit().putString("player_framing", m.name()).apply();
                    updateFraming();
                    rebuild[0].run();
                });
                card.addView(row);
            }

            View div = new View(this);
            div.setBackgroundColor(0x22ffffff);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(-1, dp(1));
            dLp.setMargins(0, dp(10), 0, dp(10));
            card.addView(div, dLp);

            card.addView(hudToggleRow("Tela limpa (ocultar HUD)", cleanScreenMode, () -> toggleCleanScreen(host)));
        };
        rebuild[0].run();
        showHudCard(host, wrap, 270);
    }

    private void toggleSettingsCard(FrameLayout host) {
        if (activeHudCard != null && "settings".equals(activeHudCard.getTag())) {
            closeActiveHudCard();
            return;
        }
        ScrollView wrap = new ScrollView(this);
        wrap.setTag("settings");
        GradientDrawable bg = rounded(0xeb14161a, 22);
        bg.setStroke(dp(1), 0x28ffffff);
        wrap.setBackground(bg);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(12), dp(14), dp(14), dp(14));
        wrap.addView(card, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        TextView back = text("‹  Ajustes", 14, Color.WHITE);
        back.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        back.setPadding(dp(8), dp(4), dp(8), dp(12));
        back.setOnClickListener(v -> closeActiveHudCard());
        sidebar.addView(back);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(4), dp(4), dp(4));

        String[] tabs = {"Geral", "Controle", "Vídeo", "Sobre"};
        final Runnable[] refreshTab = new Runnable[1];
        Runnable[] loaders = {
            () -> {
                content.removeAllViews();
                TextView h = text("Geral", 14, Color.WHITE);
                h.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                h.setPadding(0, 0, 0, dp(8));
                content.addView(h);
                content.addView(hudToggleRow("Estatísticas (FPS, latência)", showMetrics, () -> {
                    showMetrics = !showMetrics;
                    if (!showMetrics && hudPill != null) hudPill.setText("Cloud Gaming  •  ● Jogando");
                    updatePlayerOverlays();
                    refreshTab[0].run();
                }));
                content.addView(hudToggleRow("Tela limpa (ocultar HUD)", cleanScreenMode, () -> toggleCleanScreen(host)));
                TextView exitBtn = text("Sair do jogo", 13, 0xfffca5a5);
                exitBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                exitBtn.setGravity(Gravity.CENTER);
                exitBtn.setPadding(dp(12), dp(10), dp(12), dp(10));
                GradientDrawable exitBg = rounded(0x33ef4444, 12);
                exitBg.setStroke(dp(1), 0x55ef4444);
                exitBtn.setBackground(exitBg);
                LinearLayout.LayoutParams exLp = new LinearLayout.LayoutParams(-1, -2);
                exLp.topMargin = dp(12);
                exitBtn.setOnClickListener(v -> {
                    closeActiveHudCard();
                    leavePlayer(false);
                });
                content.addView(exitBtn, exLp);
            },
            () -> {
                content.removeAllViews();
                TextView h = text("Controle", 14, Color.WHITE);
                h.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                h.setPadding(0, 0, 0, dp(8));
                content.addView(h);
                content.addView(hudToggleRow("Controles virtuais", touchControlsEnabled(), () -> {
                    if (gamepadConnected) {
                        Toast.makeText(this, "Controle físico conectado: controles virtuais desativados.", Toast.LENGTH_SHORT).show();
                    } else {
                        touchControlsOverride = !touchControlsEnabled();
                        updatePlayerOverlays();
                        syncHudIndicators();
                    }
                    refreshTab[0].run();
                }));
                content.addView(hudToggleRow("Toque tátil na tela", directTouchEnabled && !gamepadConnected, () -> {
                    if (gamepadConnected) {
                        Toast.makeText(this, "Toque tátil fica desativado com controle físico.", Toast.LENGTH_SHORT).show();
                    } else {
                        directTouchEnabled = !directTouchEnabled;
                        syncHudIndicators();
                    }
                    refreshTab[0].run();
                }));
            },
            () -> {
                content.removeAllViews();
                TextView h = text("Vídeo", 14, Color.WHITE);
                h.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                h.setPadding(0, 0, 0, dp(8));
                content.addView(h);
                TextView fBtn = text("Enquadramento: " + playerFraming.label, 13, 0xffeef2f6);
                fBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
                fBtn.setBackground(rounded(0x22ffffff, 10));
                fBtn.setOnClickListener(v -> toggleFramingCard(host));
                content.addView(fBtn);
            },
            () -> {
                content.removeAllViews();
                TextView h = text("Sobre", 14, Color.WHITE);
                h.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                h.setPadding(0, 0, 0, dp(8));
                content.addView(h);
                content.addView(text("LunaNX Android\nFork GPL-3.0", 12, 0xff9aa0a6));
            }
        };
        refreshTab[0] = () -> loaders[activeSettingsTab].run();

        for (int i = 0; i < tabs.length; i++) {
            final int idx = i;
            TextView tab = text(tabs[i], 13, i == activeSettingsTab ? Color.WHITE : 0xff9aa0a6);
            tab.setPadding(dp(10), dp(8), dp(10), dp(8));
            if (i == activeSettingsTab) tab.setBackground(rounded(0x26ffffff, 100));
            tab.setOnClickListener(v -> {
                activeSettingsTab = idx;
                for (int j = 1; j < sidebar.getChildCount(); j++) {
                    View c = sidebar.getChildAt(j);
                    c.setBackground(j - 1 == idx ? rounded(0x26ffffff, 100) : null);
                    if (c instanceof TextView) ((TextView) c).setTextColor(j - 1 == idx ? Color.WHITE : 0xff9aa0a6);
                }
                loaders[idx].run();
            });
            sidebar.addView(tab);
        }
        loaders[activeSettingsTab].run();

        card.addView(sidebar, new LinearLayout.LayoutParams(dp(128), -2));
        View vDiv = new View(this);
        vDiv.setBackgroundColor(0x22ffffff);
        card.addView(vDiv, new LinearLayout.LayoutParams(dp(1), -1));
        card.addView(content, new LinearLayout.LayoutParams(0, -2, 1f));
        showHudCard(host, wrap, 380);
    }

    private View hudToggleRow(String label, boolean on, Runnable toggle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView lbl = text(label, 13, 0xffeef2f6);
        row.addView(lbl, new LinearLayout.LayoutParams(0, -2, 1f));
        FrameLayout sw = new FrameLayout(this);
        sw.setBackground(rounded(on ? 0xff22c55e : 0x44ffffff, 100));
        View thumb = new View(this);
        thumb.setBackground(rounded(Color.WHITE, 100));
        FrameLayout.LayoutParams tLp = new FrameLayout.LayoutParams(dp(16), dp(16),
            Gravity.CENTER_VERTICAL | (on ? Gravity.END : Gravity.START));
        tLp.setMargins(dp(3), 0, dp(3), 0);
        sw.addView(thumb, tLp);
        row.addView(sw, new LinearLayout.LayoutParams(dp(40), dp(22)));
        row.setOnClickListener(v -> toggle.run());
        return row;
    }

    private static final class HudIconView extends View {
        private final String kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean greenDot;

        HudIconView(android.content.Context ctx, String kind, int color, float strokePx) {
            super(ctx);
            this.kind = kind;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(strokePx);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            fillPaint.setStyle(Paint.Style.FILL);
        }

        void setGreenDot(boolean on) {
            this.greenDot = on;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float s = Math.min(w, h);
            float cx = w / 2f, cy = h / 2f;
            float r = s * 0.32f;
            if ("back".equals(kind)) {
                c.drawLine(cx + r, cy, cx - r, cy, paint);
                c.drawLine(cx - r, cy, cx - r * 0.25f, cy - r * 0.7f, paint);
                c.drawLine(cx - r, cy, cx - r * 0.25f, cy + r * 0.7f, paint);
            } else if ("gamepad".equals(kind)) {
                android.graphics.RectF body = new android.graphics.RectF(cx - r * 1.15f, cy - r * 0.65f, cx + r * 1.15f, cy + r * 0.65f);
                c.drawRoundRect(body, r * 0.55f, r * 0.55f, paint);
                c.drawLine(cx - r * 0.65f, cy, cx - r * 0.25f, cy, paint);
                c.drawLine(cx - r * 0.45f, cy - r * 0.2f, cx - r * 0.45f, cy + r * 0.2f, paint);
                fillPaint.setColor(paint.getColor());
                c.drawCircle(cx + r * 0.45f, cy - r * 0.16f, r * 0.1f, fillPaint);
                c.drawCircle(cx + r * 0.65f, cy + r * 0.12f, r * 0.1f, fillPaint);
            } else if ("screen".equals(kind)) {
                android.graphics.RectF rect = new android.graphics.RectF(cx - r, cy - r * 0.75f, cx + r, cy + r * 0.55f);
                c.drawRoundRect(rect, r * 0.22f, r * 0.22f, paint);
                c.drawLine(cx - r * 0.45f, cy + r * 0.9f, cx + r * 0.45f, cy + r * 0.9f, paint);
            } else if ("gear".equals(kind)) {
                c.drawCircle(cx, cy, r * 0.36f, paint);
                c.drawCircle(cx, cy, r * 0.78f, paint);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4.0;
                    c.drawLine(
                        cx + (float) Math.cos(a) * r * 0.78f,
                        cy + (float) Math.sin(a) * r * 0.78f,
                        cx + (float) Math.cos(a) * r * 1.05f,
                        cy + (float) Math.sin(a) * r * 1.05f, paint);
                }
            } else if ("cloud".equals(kind)) {
                android.graphics.Path p = new android.graphics.Path();
                p.moveTo(cx - r * 0.65f, cy + r * 0.45f);
                p.lineTo(cx + r * 0.68f, cy + r * 0.45f);
                p.cubicTo(cx + r * 1.15f, cy + r * 0.45f, cx + r * 1.1f, cy - r * 0.25f, cx + r * 0.55f, cy - r * 0.22f);
                p.cubicTo(cx + r * 0.45f, cy - r * 0.78f, cx - r * 0.35f, cy - r * 0.78f, cx - r * 0.42f, cy - r * 0.18f);
                p.cubicTo(cx - r * 1.05f, cy - r * 0.2f, cx - r * 1.1f, cy + r * 0.45f, cx - r * 0.65f, cy + r * 0.45f);
                c.drawPath(p, paint);
            } else if ("pointer".equals(kind)) {
                android.graphics.Path p = new android.graphics.Path();
                p.moveTo(cx - r * 0.45f, cy - r * 0.75f);
                p.lineTo(cx - r * 0.45f, cy + r * 0.65f);
                p.lineTo(cx - r * 0.08f, cy + r * 0.32f);
                p.lineTo(cx + r * 0.28f, cy + r * 0.85f);
                p.lineTo(cx + r * 0.48f, cy + r * 0.72f);
                p.lineTo(cx + r * 0.14f, cy + r * 0.22f);
                p.lineTo(cx + r * 0.62f, cy + r * 0.18f);
                p.close();
                c.drawPath(p, paint);
            } else if ("keyboard".equals(kind)) {
                android.graphics.RectF rect = new android.graphics.RectF(cx - r * 1.15f, cy - r * 0.72f, cx + r * 1.15f, cy + r * 0.72f);
                c.drawRoundRect(rect, r * 0.24f, r * 0.24f, paint);
                c.drawLine(cx - r * 0.65f, cy - r * 0.22f, cx - r * 0.35f, cy - r * 0.22f, paint);
                c.drawLine(cx - r * 0.1f, cy - r * 0.22f, cx + r * 0.2f, cy - r * 0.22f, paint);
                c.drawLine(cx + r * 0.45f, cy - r * 0.22f, cx + r * 0.75f, cy - r * 0.22f, paint);
                c.drawLine(cx - r * 0.55f, cy + r * 0.28f, cx + r * 0.55f, cy + r * 0.28f, paint);
            } else if ("crown".equals(kind)) {
                android.graphics.Path p = new android.graphics.Path();
                p.moveTo(cx - r * 0.9f, cy + r * 0.45f);
                p.lineTo(cx - r * 0.95f, cy - r * 0.35f);
                p.lineTo(cx - r * 0.4f, cy + r * 0.05f);
                p.lineTo(cx, cy - r * 0.55f);
                p.lineTo(cx + r * 0.4f, cy + r * 0.05f);
                p.lineTo(cx + r * 0.95f, cy - r * 0.35f);
                p.lineTo(cx + r * 0.9f, cy + r * 0.45f);
                p.close();
                c.drawPath(p, paint);
            }
            if (greenDot) {
                fillPaint.setColor(0xff22c55e);
                c.drawCircle(w - s * 0.22f, h - s * 0.22f, s * 0.11f, fillPaint);
            }
        }
    }

    private FrameLayout playerCircleVectorIcon(String kind, boolean greenDot, String description, Runnable action) {
        FrameLayout wrap = new FrameLayout(this);
        GradientDrawable bg = rounded(0x8814161a, 100);
        bg.setStroke(dp(1), 0x33ffffff);
        wrap.setBackground(bg);
        HudIconView icon = new HudIconView(this, kind, 0xfff2f4f8, dp(1.6f));
        icon.setGreenDot(greenDot);
        wrap.addView(icon, new FrameLayout.LayoutParams(-1, -1));
        wrap.setContentDescription(description);
        wrap.setOnClickListener(v -> {
            animateTap(wrap);
            action.run();
        });
        return wrap;
    }

    private TextView playerCircleIcon(String symbol, String description, Runnable action) {
        TextView icon = text(symbol, 16, 0xfff2f4f8);
        icon.setGravity(Gravity.CENTER);
        GradientDrawable bg = rounded(0x8814161a, 100);
        bg.setStroke(dp(1), 0x33ffffff);
        icon.setBackground(bg);
        icon.setContentDescription(description);
        icon.setOnClickListener(view -> action.run());
        return icon;
    }

    private TextView playerShortcut(String label, String description, Runnable action) {
        TextView shortcut = text(label, 12, 0xfff2eaff);
        shortcut.setGravity(Gravity.CENTER);
        shortcut.setPadding(dp(10), 0, dp(10), 0);
        shortcut.setBackground(tapBackground(0xb322192f, 15));
        shortcut.setContentDescription(description);
        shortcut.setOnClickListener(view -> action.run());
        return shortcut;
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

    private void changeMicrophone(boolean enabled) {
        long token = operation;
        worker.execute(() -> {
            if (isCancelled(token)) return;
            boolean changed = nativeSetMicrophone(enabled);
            microphoneEnabled = enabled && changed;
            post(token, () -> Toast.makeText(this, changed
                ? (enabled ? "Microfone ativado" : "Microfone desativado")
                : "Não foi possível abrir o microfone. Confira a permissão e tente novamente.",
                Toast.LENGTH_LONG).show());
        });
    }

    private void toggleMicrophone() {
        if (microphoneEnabled) {
            changeMicrophone(false);
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            microphonePermissionOperation = operation;
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 701);
            return;
        }
        changeMicrophone(true);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != 701 || player == null || launching ||
                operation != microphonePermissionOperation) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
            changeMicrophone(true);
        else Toast.makeText(this, "Permita o acesso ao microfone para falar no jogo.",
            Toast.LENGTH_LONG).show();
    }

    private void showPlayerMenu() {
        if (player == null || launching || playerMenu != null) return;
        resetPad();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(14), dp(18), dp(14));
        content.setBackground(rounded(BACKGROUND, 16));

        TextView title = heading(playerTitle);
        title.setTextSize(21);
        title.setMaxLines(2);
        content.addView(title);
        content.addView(text(playerProfile.name + " · " + playerProfile.videoSummary(), 13, MUTED));
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
                if (!playerProfile.custom())
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
        Button touch = playerAction(touchAction(), view -> {
            touchControlsOverride = !touchControlsEnabled();
            ((Button) view).setText(touchAction());
            updatePlayerOverlays();
        });
        LinearLayout.LayoutParams touchPosition = new LinearLayout.LayoutParams(0, dp(44), 1);
        touchPosition.setMarginStart(dp(5));
        actions.addView(touch, touchPosition);
        Button microphone = playerAction(microphoneEnabled ? "Desativar microfone" : "Ativar microfone", view -> {
            if (playerMenu != null) playerMenu.dismiss();
            toggleMicrophone();
        });
        LinearLayout.LayoutParams microphonePosition = new LinearLayout.LayoutParams(-1, dp(44));
        microphonePosition.topMargin = dp(6);
        content.addView(microphone, microphonePosition);
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

    private boolean touchControlsEnabled() {
        if (gamepadConnected) return false;
        return touchControlsOverride == null ? true : touchControlsOverride;
    }

    private String touchAction() {
        return touchControlsEnabled() ? "Ocultar controles" : "Mostrar controles";
    }

    private void updatePlayerOverlays() {
        if (controls == null || player == null) return;
        boolean showTouch = !launching && !cleanScreenMode && touchControlsEnabled();
        if (showTouch) controls.setAlpha(1f);
        controls.setVisibility(showTouch ? View.VISIBLE : View.GONE);
        if (!showTouch) resetTouchPad();
        updatePlayerShortcuts();
        metrics.setVisibility(View.GONE);
    }

    private void updatePlayerShortcuts() {
        if (playerMenuButton == null || player == null) return;
        boolean visible = !launching && !cleanScreenMode && (playerShortcutsVisible || activeHudCard != null);
        int vis = visible ? View.VISIBLE : View.GONE;
        playerMenuButton.setVisibility(vis);
        if (hudPillWrap != null) hudPillWrap.setVisibility(vis);
        if (hudRightBar != null) hudRightBar.setVisibility(vis);
        if (showTouchButton != null) showTouchButton.setVisibility(vis);
    }

    private void setPlayerShortcuts(boolean visible) {
        playerShortcutsVisible = visible;
        updatePlayerShortcuts();
    }

    private void revealPlayerShortcuts() {
        ui.removeCallbacks(hidePlayerShortcuts);
        setPlayerShortcuts(true);
        ui.postDelayed(hidePlayerShortcuts, 3000);
    }

    private static boolean isGamepad(InputDevice device) {
        if (device == null || device.isVirtual()) return false;
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private void refreshGamepads() {
        if (player == null) return;
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
        updatePlayerOverlays();
        syncHudIndicators();
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
                if (launching) {
                    showLaunchProgress(ENDED, videoSurfaceError, false, -1, -1);
                } else {
                    streamStatus.setText(videoSurfaceError);
                    streamStatus.setVisibility(View.VISIBLE);
                }
            });
        }
    }

    @SuppressWarnings("deprecation")
    private void acquirePlaybackWifiLock() {
        if (playbackWifiLock != null) return;
        android.net.wifi.WifiManager wifi = getApplicationContext()
            .getSystemService(android.net.wifi.WifiManager.class);
        if (wifi == null) return;
        int mode = android.os.Build.VERSION.SDK_INT >= 29
            ? android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            : android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF;
        android.net.wifi.WifiManager.WifiLock lock = wifi.createWifiLock(mode, "LunaNX:playback");
        lock.setReferenceCounted(false);
        try {
            lock.acquire();
            playbackWifiLock = lock;
        } catch (SecurityException error) {
            android.util.Log.w("LunaNX", "Could not acquire playback Wi-Fi lock", error);
        }
    }

    private void releasePlaybackWifiLock() {
        if (playbackWifiLock == null) return;
        if (playbackWifiLock.isHeld()) playbackWifiLock.release();
        playbackWifiLock = null;
    }

    private void startPlayback(long token, Surface target) {
        if (isCancelled(token)) return;
        try {
            boolean preferPortuguese = getPreferences(MODE_PRIVATE).getBoolean("prefer_pt_br", false);
            StreamProfile profile = playerProfile;
            acquirePlaybackWifiLock();
            nativeStartByKey(selectedGameKey, target, preferPortuguese, profile.height, profile.fps, profile.bitrateMbps);
            PlaybackReport report = playbackReport;
            playbackTicker = worker.scheduleWithFixedDelay(new Runnable() {
                long checkedAt;
                long sampledAt;
                long lastBytes;
                long lastReceived;
                long lastDecoded;
                int previousRumble;
                @Override public void run() {
                    if (isCancelled(token)) return;
                    Pad input = pad;
                    int rumble = nativeTick(input.buttons, input.lt, input.rt, input.x, input.y, input.z, input.rz);
                    if (rumble != previousRumble) {
                        previousRumble = rumble;
                        post(token, () -> updateRumble(rumble));
                    }
                    if (microphoneEnabled && !nativeMicrophoneActive()) {
                        microphoneEnabled = false;
                        post(token, () -> Toast.makeText(MainActivity.this,
                            "Microfone interrompido. Ative novamente no menu do player.",
                            Toast.LENGTH_LONG).show());
                    }
                    long now = SystemClock.elapsedRealtime();
                    if (now - checkedAt < 250) return;
                    checkedAt = now;
                    String[] status = nativeStatus();
                    if (status == null) return;
                    int phase = Integer.parseInt(status[0]);
                    if (phase >= ENDED) releasePlaybackWifiLock();
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
                            String bitrate = String.format(java.util.Locale.ROOT, "%.1f", mbps);
                            String details = String.format(java.util.Locale.ROOT,
                                "Entrada %d FPS · fila %s ms\nRTP danificado %s · aguardando chave %s\nResets %s · erros de codec %s · fila cheia %s\nCodec %s pendentes · pico %s ms · sem imagem %s",
                                receivedFps, status[8], status[6], status[15],
                                status[7], status[10], status[11], status[12], status[13], status[14]);
                            post(token, () -> {
                                if (showMetrics) {
                                    metricValues[0].setText(Long.toString(decodedFps));
                                    metricValues[1].setText(bitrate);
                                    metricValues[2].setText(status[9] + " ms");
                                    metricDetails.setText(details);
                                }
                                if (hudPill != null) {
                                    hudPill.setText(showMetrics
                                        ? "Cloud Gaming  •  ● Jogando • " + decodedFps + " FPS • " + bitrate + " Mb/s • " + status[9] + " ms"
                                        : "Cloud Gaming  •  ● Jogando");
                                }
                            });
                        }
                        sampledAt = now;
                        lastBytes = bytes;
                        lastReceived = received;
                        lastDecoded = decoded;
                    }
                    post(token, () -> {
                        if (launching && phase != STREAMING) {
                            if (videoSurfaceError != null && phase < ENDED)
                                showLaunchProgress(ENDED, videoSurfaceError, false, -1, -1);
                            else
                                showLaunchProgress(phase, status[1], "1".equals(status[18]),
                                    Integer.parseInt(status[19]), Integer.parseInt(status[20]));
                            return;
                        }
                        if (launching) {
                            launching = false;
                            launchPanel.setVisibility(View.GONE);
                            updatePlayerOverlays();
                            revealPlayerShortcuts();
                        }
                        boolean showSurfaceError = phase < ENDED && videoSurfaceError != null;
                        boolean showStatus = phase != STREAMING || showSurfaceError;
                        int visibility = showStatus ? View.VISIBLE : View.GONE;
                        if (streamStatus.getVisibility() != visibility)
                            streamStatus.setVisibility(visibility);
                        String message = showSurfaceError ? videoSurfaceError : status[1];
                        if (showStatus && !android.text.TextUtils.equals(streamStatus.getText(), message))
                            streamStatus.setText(message);
                    });
                    if (phase >= ENDED) {
                        nativeStop();
                        playbackTicker.cancel(false);
                    }
                }
            }, 0, 16, TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            releasePlaybackWifiLock();
            post(token, () -> showLaunchProgress(ENDED, message(error), false, -1, -1));
        }
    }

    private void leavePlayer() {
        leavePlayer(!launching);
    }

    private void leavePlayer(boolean showReport) {
        if (player == null) return;
        closeActiveHudCard();
        PlaybackReport report = showReport ? playbackReport : null;
        ++operation;
        if (playbackTicker != null) {
            playbackTicker.cancel(false);
            playbackTicker = null;
        }
        player = null;
        activeHudCard = null;
        hudPillWrap = null;
        hudRightBar = null;
        cleanScreenMode = false;
        hudPill = null;
        ui.removeCallbacks(hidePlayerShortcuts);
        if (playerMenu != null) playerMenu.dismiss();
        playerSurface = null;
        controls = null;
        playerMenuButton = null;
        showTouchButton = null;
        updateRumble(0);
        controllerDevice = -1;
        gamepadConnected = false;
        resetPad();
        stopPlaybackAsync(report);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(0);
        setRequestedOrientation(returnOrientation);
        if (home) {
            if (homeRows != null) {
                showHome(homeRows);
                refreshHomeRows();
            } else {
                openHome();
            }
        } else if (favoritesScreen) {
            showFavorites();
        } else if (games != null) {
            showLibrary(games, false);
            if (section == RECENT_GAMES) loadSection(RECENT_GAMES);
        } else {
            openHome();
        }
        startCatalogDiscovery(false);
    }

    private void stopPlaybackAsync(PlaybackReport report) {
        microphoneEnabled = false;
        String game = playerTitle;
        String profileName = playerProfile == null ? "" : playerProfile.name;
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
            try {
                nativeStop();
            } finally {
                releasePlaybackWifiLock();
            }
            if (report == null) return;
            String summary = report.format(now);
            String[] stats = report.highlights(now);
            ui.post(() -> {
                if (!destroyed && player == null && libraryVisible && playbackReport == report)
                    showPlaybackReport(game, profileName, stats, summary);
            });
        });
    }

    private LinearLayout statCell(LinearLayout row, String label) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(8), dp(6), dp(8), dp(6));
        row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        cell.addView(text(label, 11, 0xffc9b8da));
        return cell;
    }

    private TextView statValue(LinearLayout cell, String value) {
        TextView number = text(value, 17, Color.WHITE);
        number.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        cell.addView(number);
        return number;
    }

    private void showPlaybackReport(String game, String profileName, String[] stats, String summary) {
        Dialog dialog = new Dialog(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(chrome(0xff20172d, 26));
        scroll.setClipToOutline(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(16));
        scroll.addView(panel);
        TextView title = heading("Relatório da partida");
        title.setTextSize(23);
        panel.addView(title);
        TextView session = text(game + " · " + stats[0] + "\nPerfil " + profileName, 14, 0xffc9b8da);
        session.setPadding(0, dp(4), 0, dp(12));
        panel.addView(session);

        String[] labels = {"FPS decodificado", "Bitrate recebido", "Programados para a tela", "Janelas sem decodificação"};
        for (int i = 0; i < labels.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setBackground(chrome(0xff302040, 12));
            LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2);
            spacing.bottomMargin = dp(8);
            panel.addView(row, spacing);
            statValue(statCell(row, labels[i]), stats[i + 1]);
            statValue(statCell(row, labels[i + 1]), stats[i + 2]);
        }

        TextView report = text(summary, 13, 0xffe0d4ef);
        report.setTextIsSelectable(true);
        report.setPadding(0, dp(8), 0, dp(12));
        report.setVisibility(View.GONE);
        panel.addView(libraryAction("Detalhes técnicos", false, () ->
            report.setVisibility(report.getVisibility() == View.GONE ? View.VISIBLE : View.GONE)));
        panel.addView(report);
        LinearLayout actions = new LinearLayout(this);
        LinearLayout.LayoutParams actionSpacing = new LinearLayout.LayoutParams(-1, -2);
        actionSpacing.topMargin = dp(8);
        panel.addView(actions, actionSpacing);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1f);
        copyParams.rightMargin = dp(8);
        actions.addView(libraryAction("Copiar relatório", true, () -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Relatório de partida LunaNX", summary));
                Toast.makeText(this, "Relatório copiado", Toast.LENGTH_SHORT).show();
            }
        }), copyParams);
        actions.addView(libraryAction("Fechar", false, dialog::dismiss),
            new LinearLayout.LayoutParams(0, -2, 1f));
        showLibraryDialog(dialog, scroll);
    }

    // LandscapeTouchControls and Classic skin ported from OpenNOW Android (Zortos, MIT).
    private View touchOverlay() {
        FrameLayout panel = new FrameLayout(this);
        panel.setMotionEventSplittingEnabled(true);
        Configuration screen = getResources().getConfiguration();
        float fit = Math.min(1f, Math.min(screen.screenWidthDp / 760f, screen.screenHeightDp / 385f));
        float scale = 0.9f * fit;
        float buttons = 1.102468f * scale;
        float side = 38 * fit;
        float bottom = 34 * fit;
        float shoulderWidth = 76 * buttons;
        float shoulderHeight = 36 * buttons;
        float shoulderHit = Math.max(48 * fit, shoulderHeight);
        float clearance = Math.max(34, Math.min(58, (screen.screenHeightDp - 20) * 0.11f));
        float top = 34 * fit + clearance * Math.max(0.75f, buttons);
        int topLeft = Gravity.TOP | Gravity.LEFT;
        int topRight = Gravity.TOP | Gravity.RIGHT;
        int bottomLeft = Gravity.BOTTOM | Gravity.LEFT;
        int bottomRight = Gravity.BOTTOM | Gravity.RIGHT;
        int bottomCenter = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;

        placeTouch(panel, touchKey("LT", TOUCH_LT, shoulderHeight, shoulderHit),
            shoulderWidth, shoulderHit, topLeft, side, top);
        placeTouch(panel, touchKey("LB", 0x0100, shoulderHeight, shoulderHit),
            shoulderWidth, shoulderHit, topLeft, side + 44 * fit, top + shoulderHit + 8 * fit);
        placeTouch(panel, touchKey("RT", TOUCH_RT, shoulderHeight, shoulderHit),
            shoulderWidth, shoulderHit, topRight, side, top);
        placeTouch(panel, touchKey("RB", 0x0200, shoulderHeight, shoulderHit),
            shoulderWidth, shoulderHit, topRight, side + 44 * fit, top + shoulderHit + 8 * fit);

        float stick = 116 * scale;
        float dpad = 54 * buttons * 0.88f * 2.8f;
        float dpadLeft = side + stick + 14 * fit;
        placeTouch(panel, new TouchStick(true), stick, stick, bottomLeft, side, bottom + 54 * fit);
        placeTouch(panel, new TouchDpad(), dpad, dpad, bottomLeft, dpadLeft, bottom + 40 * fit);

        float face = 54 * buttons * 0.9f;
        float faceHit = Math.max(48 * fit, face);
        float distance = faceHit * 1.05f;
        float faceWidth = faceHit + 2 * distance;
        float rightStick = side + faceWidth + 14 * fit;
        placeTouch(panel, new TouchStick(false), stick, stick, bottomRight, rightStick, bottom + 28 * fit);
        addTouchCap(panel, "A", 0x1000, face, fit, bottomRight, side + distance, bottom + 46 * fit);
        addTouchCap(panel, "B", 0x2000, face, fit, bottomRight, side, bottom + 46 * fit + distance);
        addTouchCap(panel, "X", 0x4000, face, fit, bottomRight, side + 2 * distance, bottom + 46 * fit + distance);
        addTouchCap(panel, "Y", 0x8000, face, fit, bottomRight, side + distance, bottom + 46 * fit + 2 * distance);

        float cornerSize = 40 * buttons;
        addTouchCap(panel, "⧉", 0x0020, cornerSize, fit, bottomLeft, side, bottom - 12 * fit);
        addTouchCap(panel, "⛶", 0x0010, cornerSize, fit, bottomRight, side, bottom - 12 * fit);
        return panel;
    }

    private void addTouchCap(FrameLayout panel, String label, int mask, float diameter, float fit,
                             int anchor, float horizontal, float vertical) {
        float hit = Math.max(48 * fit, diameter);
        placeTouch(panel, touchKey(label, mask, diameter, hit), hit, hit, anchor, horizontal, vertical);
    }

    private void placeTouch(FrameLayout panel, View view, float width, float height, int anchor,
                            float horizontal, float vertical) {
        FrameLayout.LayoutParams bounds = new FrameLayout.LayoutParams(dp(width), dp(height), anchor);
        switch (anchor & Gravity.HORIZONTAL_GRAVITY_MASK) {
            case Gravity.LEFT: bounds.leftMargin = dp(horizontal); break;
            case Gravity.RIGHT: bounds.rightMargin = dp(horizontal); break;
            default:
                // FrameLayout adds leftMargin and subtracts rightMargin for centered children.
                bounds.leftMargin = Math.max(0, dp(horizontal));
                bounds.rightMargin = Math.max(0, dp(-horizontal));
        }
        if ((anchor & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP)
            bounds.topMargin = dp(vertical);
        else
            bounds.bottomMargin = dp(vertical);
        panel.addView(view, bounds);
    }

    private View touchKey(String label, int mask, float size, float hitHeight) {
        TextView key = text(label, 13, Color.WHITE);
        key.setGravity(Gravity.CENTER);
        key.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        key.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_pressed}, {}},
            new int[]{0xd1ffffff, 0xbcffffff}));
        int inset = dp((hitHeight - size) / 2);
        boolean pill = mask == TOUCH_LT || mask == TOUCH_RT || mask == 0x100 || mask == 0x200;
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, new android.graphics.drawable.InsetDrawable(
            classicPadShape(0x2affffff), pill ? 0 : inset, inset, pill ? 0 : inset, inset));
        states.addState(new int[]{}, new android.graphics.drawable.InsetDrawable(
            classicPadShape(0x7d000000), pill ? 0 : inset, inset, pill ? 0 : inset, inset));
        key.setBackground(states);
        key.setContentDescription(mask == 0x20 ? "View" : mask == 0x10 ? "Menu" : mask == 0x400 ? "Guide" : label);
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
        return key;
    }

    private GradientDrawable classicPadShape(int fill) {
        GradientDrawable shape = rounded(fill, 100);
        shape.setStroke(dp(1), 0x54ffffff);
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
                dp(8), dp(8), Path.Direction.CW);
            Path horizontal = new Path();
            horizontal.addRoundRect(inset, (height - arm) / 2, width - inset, (height + arm) / 2,
                dp(8), dp(8), Path.Direction.CW);
            cross.op(horizontal, Path.Op.UNION);
        }

        @Override protected void onDraw(Canvas canvas) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x7d000000);
            canvas.drawPath(cross, paint);
            float width = getWidth(), height = getHeight();
            float x = width / 2, y = height / 2;
            canvas.save();
            canvas.clipPath(cross);
            paint.setColor(0x2affffff);
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
            paint.setColor(0x54ffffff);
            canvas.drawPath(cross, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int next = 0;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    float x = event.getX() - getWidth() / 2f;
                    float y = event.getY() - getHeight() / 2f;
                    if (Math.hypot(x, y) > dp(12)) {
                        if (Math.abs(x) > 0.414f * Math.abs(y)) next |= x < 0 ? 4 : 8;
                        if (Math.abs(y) > 0.414f * Math.abs(x)) next |= y < 0 ? 1 : 2;
                    }
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
            paint.setColor(0x3fffffff);
            float size = Math.min(getWidth(), getHeight());
            canvas.drawCircle(cx, cy, size / 2 - dp(1), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xa7cccccc);
            canvas.drawCircle(cx + x * size * 0.34f, cy - y * size * 0.34f, size * 0.22f, paint);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    float radius = Math.min(getWidth(), getHeight()) * 0.34f;
                    float rawX = (event.getX() - getWidth() / 2f) / radius;
                    float rawY = (getHeight() / 2f - event.getY()) / radius;
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
            float length = (float) Math.hypot(x, y);
            float output = length > 0.05f ? (length - 0.05f) / (0.95f * length) : 0;
            if (left) { touchLx = x * output; touchLy = y * output; }
            else { touchRx = x * output; touchRy = y * output; }
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
            if (launching && key == KeyEvent.KEYCODE_BUTTON_B) {
                if (!pressed) leavePlayer(false);
                return true;
            }
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
        if (gameDetailOverlay != null) {
            closeGameDetail();
            return;
        }
        if (player != null) {
            if (cleanScreenMode) {
                toggleCleanScreen(player);
                return;
            }
            if (activeHudCard != null) {
                closeActiveHudCard();
                return;
            }
            if (launching) leavePlayer(false);
            else toggleSettingsCard(player);
        } else if (authenticating) cancelSignIn();
        else if (libraryVisible && settingsScreen) {
            ++operation;
            loadingGames = false;
            openHome();
        } else if (libraryVisible && inlineSearch) {
            ++operation;
            loadingGames = false;
            inlineSearch = false;
            loadSection(ALL_GAMES);
        } else if (libraryVisible && favoritesScreen) {
            ++operation;
            loadingGames = false;
            favoritesScreen = false;
            openHome();
        } else if (libraryVisible && !search.isEmpty()) {
            ++operation;
            loadingGames = false;
            loadSection(ALL_GAMES);
        } else if (libraryVisible && (!home || !homeGenre.isEmpty())) {
            ++operation;
            loadingGames = false;
            openHome();
        } else super.onBackPressed();
    }

    @Override protected void onPause() {
        ui.removeCallbacks(hidePlayerShortcuts);
        if (player != null) {
            resetPad();
            updateRumble(0);
            if (activePointerId != -1) {
                activePointerId = -1;
                pointerWorker.execute(() -> nativeSendPointerButton(false));
            }
            if (microphoneEnabled) {
                microphoneEnabled = false;
                worker.execute(() -> nativeSetMicrophone(false));
            }
        }
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
