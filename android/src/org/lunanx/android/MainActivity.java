package org.lunanx.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
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
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
    private static final int STREAMING = 3;
    private static final int ENDED = 4;

    private static final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private static final ExecutorService artwork = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> covers = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile long operation;
    private volatile boolean destroyed;
    private boolean authenticating;
    private boolean loadingGames;
    private FrameLayout player;
    private GridView grid;
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
    private native String[] nativeExplore(long token);
    private native void nativeStart(int index, Surface surface, boolean preferBrazilianPortuguese);
    private native int nativeTick(int buttons, float lt, float rt, float lx, float ly, float rx, float ry);
    private native String[] nativeStatus();
    private native boolean nativeSetVideoSurface(Surface surface);
    private native void nativeStop();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        inputManager = getSystemService(InputManager.class);
        if (inputManager != null) inputManager.registerInputDeviceListener(inputListener, ui);
        showLoading("Carregando a sessão Amazon…");
        long token = ++operation;
        worker.execute(() -> {
            try {
                prepareTrustStore();
                nativeInit(getFilesDir().getAbsolutePath());
                markets = nativeMarkets();
                String[] library = nativeLoad();
                post(token, () -> {
                    if (library == null) showSignIn(null);
                    else showLibrary(library);
                });
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
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(16), dp(18), dp(16));
        content.setBackgroundColor(BACKGROUND);
        if (center) content.setGravity(Gravity.CENTER);
        setContentView(content);
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
                if (library != null) post(token, () -> {
                    authenticating = false;
                    showLibrary(library);
                });
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

    private void showLibrary(String[] library) {
        games = library;
        loadingGames = false;
        LinearLayout content = screen(false);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(heading("LunaNX"), new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(button("Conta", this::showAccount));
        content.addView(header);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setHint("Buscar jogos");
        query.setText(search);
        Runnable submit = () -> {
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(query.getWindowToken(), 0);
            query.clearFocus();
            loadGames(query.getText().toString().trim());
        };
        query.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            submit.run();
            return true;
        });
        searchRow.addView(query, new LinearLayout.LayoutParams(0, dp(48), 1));
        searchRow.addView(button("Buscar", submit));
        content.addView(searchRow);

        LinearLayout sections = new LinearLayout(this);
        sections.addView(button("Todos", () -> loadSection(0)), new LinearLayout.LayoutParams(0, -2, 1));
        sections.addView(button("Top 10", () -> loadSection(1)), new LinearLayout.LayoutParams(0, -2, 1));
        sections.addView(button("Continuar", () -> loadSection(2)), new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(sections);

        libraryStatus = text("", 14, MUTED);
        libraryStatus.setPadding(0, dp(8), 0, dp(8));
        content.addView(libraryStatus);
        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(138));
        grid.setHorizontalSpacing(dp(10));
        grid.setVerticalSpacing(dp(10));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (!loadingGames) showPlayer(position);
        });
        content.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        renderGames();
    }

    private void renderGames() {
        int count = games.length / 3;
        String label = !search.isEmpty() ? "Resultados" :
            section == 1 ? "Top 10" : section == 2 ? "Continue jogando" : "Biblioteca";
        libraryStatus.setText(label + " · " + (count == 0 ? "Nenhum jogo encontrado" :
            count + (count == 1 ? " jogo" : " jogos")));
        grid.setAdapter(new GameAdapter(games));
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
                    games = result;
                    search = query;
                    section = 0;
                    loadingGames = false;
                    renderGames();
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

    private void exploreCatalog() {
        showLoading("Procurando mais jogos no catálogo…");
        long token = ++operation;
        worker.execute(() -> {
            try {
                String[] result = nativeExplore(token);
                post(token, () -> {
                    section = 0;
                    search = "";
                    showLibrary(result);
                });
            } catch (Exception error) {
                post(token, () -> {
                    showLibrary(games);
                    libraryStatus.setText(message(error));
                });
            }
        });
    }

    private void showAccount() {
        if (loadingGames) return;
        new AlertDialog.Builder(this)
            .setTitle("Conta Amazon")
            .setItems(new String[]{"Recarregar biblioteca", "Carregar mais jogos", "Idioma dos jogos", "Sair da conta"}, (dialog, which) -> {
                if (which == 0) refreshLibrary();
                else if (which == 1) exploreCatalog();
                else if (which == 2) showGameLanguage();
                else signOut();
            }).show();
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
        showLoading("Atualizando a biblioteca…");
        long token = ++operation;
        worker.execute(() -> {
            try {
                String[] result = nativeLoad();
                post(token, () -> {
                    section = 0;
                    search = "";
                    if (result == null) showSignIn("A sessão expirou. Gere um novo código.");
                    else showLibrary(result);
                });
            } catch (Exception error) {
                post(token, () -> {
                    showLibrary(games);
                    libraryStatus.setText(message(error));
                });
            }
        });
    }

    private void signOut() {
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

    private final class GameAdapter extends BaseAdapter {
        final String[] rows;
        GameAdapter(String[] rows) { this.rows = rows; }
        @Override public int getCount() { return rows.length / 3; }
        @Override public Object getItem(int position) { return rows[position * 3]; }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout tile;
            if (recycled == null) {
                tile = new LinearLayout(MainActivity.this);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setBackground(rounded(CARD, 12));
                tile.setClipToOutline(true);
                ImageView cover = new ImageView(MainActivity.this);
                cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
                tile.addView(cover, new LinearLayout.LayoutParams(-1, dp(132)));
                TextView name = text("", 15, Color.WHITE);
                name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                name.setMaxLines(2);
                name.setPadding(dp(10), dp(8), dp(10), 0);
                tile.addView(name, new LinearLayout.LayoutParams(-1, dp(48)));
                TextView badge = text("", 11, MUTED);
                badge.setSingleLine();
                badge.setPadding(dp(10), 0, dp(10), dp(3));
                tile.addView(badge, new LinearLayout.LayoutParams(-1, dp(24)));
            } else {
                tile = (LinearLayout) recycled;
            }
            int offset = position * 3;
            ((TextView) tile.getChildAt(1)).setText(rows[offset]);
            ((TextView) tile.getChildAt(2)).setText(rows[offset + 2]);
            bindCover((ImageView) tile.getChildAt(0), rows[offset + 1]);
            return tile;
        }
    }

    private void bindCover(ImageView image, String url) {
        image.setTag(url);
        Bitmap cached = covers.get(url);
        image.setImageBitmap(cached);
        if (url.isEmpty() || cached != null) return;
        artwork.execute(() -> {
            if (destroyed || covers.get(url) != null) return;
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
                        if (!destroyed && url.equals(image.getTag())) image.setImageBitmap(decoded);
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
        selectedGame = index;
        playerTitle = games[index * 3];
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
                    if (sampledAt == 0 || now - sampledAt >= 1000) {
                        long elapsed = now - sampledAt;
                        if (sampledAt != 0) {
                            double mbps = (bytes - lastBytes) * 0.008 / elapsed;
                            long receivedFps = Math.round((received - lastReceived) * 1000.0 / elapsed);
                            long decodedFps = Math.round((decoded - lastDecoded) * 1000.0 / elapsed);
                            metricsLabel = String.format(java.util.Locale.ROOT,
                                "FPS entra %d / dec %d · %.1f Mb/s\nFila %s ms · var. %s ms · desc. RTP %s\nResets %s · codec %s · fila cheia %s",
                                receivedFps, decodedFps, mbps, status[8], status[9],
                                status[6], status[7], status[10], status[11]);
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
        stopPlaybackAsync();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(0);
        setRequestedOrientation(returnOrientation);
        showLibrary(games);
        if (section == 2) loadSection(2);
    }

    private void stopPlaybackAsync() {
        worker.execute(() -> {
            if (playbackTicker != null) playbackTicker.cancel(false);
            playbackTicker = null;
            nativeStop();
        });
    }

    private View touchOverlay() {
        FrameLayout panel = new FrameLayout(this);
        LinearLayout left = new LinearLayout(this);
        left.setGravity(Gravity.BOTTOM);
        left.addView(new TouchStick(true), new LinearLayout.LayoutParams(dp(100), dp(100)));
        LinearLayout leftButtons = new LinearLayout(this);
        leftButtons.setOrientation(LinearLayout.VERTICAL);
        leftButtons.addView(padRow(new String[]{"LB", "LT", "↖"}, new int[]{0x100, TOUCH_LT, 0x20}));
        leftButtons.addView(padRow(new String[]{"↑"}, new int[]{1}));
        leftButtons.addView(padRow(new String[]{"←", "↓", "→"}, new int[]{4, 2, 8}));
        left.addView(leftButtons);
        panel.addView(left, floating(-2, -2, Gravity.BOTTOM | Gravity.START, 8));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.BOTTOM);
        LinearLayout rightButtons = new LinearLayout(this);
        rightButtons.setOrientation(LinearLayout.VERTICAL);
        rightButtons.addView(padRow(new String[]{"↗", "RT", "RB"}, new int[]{0x10, TOUCH_RT, 0x200}));
        rightButtons.addView(padRow(new String[]{"Y"}, new int[]{0x8000}));
        rightButtons.addView(padRow(new String[]{"X", "A", "B"}, new int[]{0x4000, 0x1000, 0x2000}));
        right.addView(rightButtons);
        right.addView(new TouchStick(false), new LinearLayout.LayoutParams(dp(100), dp(100)));
        panel.addView(right, floating(-2, -2, Gravity.BOTTOM | Gravity.END, 8));
        return panel;
    }

    private LinearLayout padRow(String[] labels, int[] masks) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        for (int i = 0; i < labels.length; i++) {
            int mask = masks[i];
            TextView key = text(labels[i], 15, Color.WHITE);
            key.setGravity(Gravity.CENTER);
            android.graphics.drawable.StateListDrawable keyBackground = new android.graphics.drawable.StateListDrawable();
            keyBackground.addState(new int[]{android.R.attr.state_pressed}, rounded(0xee58448e, 24));
            keyBackground.addState(new int[]{}, rounded(mask == 0x1000 ? 0xa64a367e : 0x951c2030, 24));
            key.setBackground(keyBackground);
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
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(dp(40), dp(40));
            layout.setMargins(dp(2), dp(2), dp(2), dp(2));
            row.addView(key, layout);
        }
        return row;
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
            paint.setColor(0x951c2030);
            canvas.drawCircle(cx, cy, Math.min(cx, cy) - dp(2), paint);
            paint.setColor(0xa0ae9aff);
            canvas.drawCircle(cx + x * dp(28), cy - y * dp(28), dp(16), paint);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    float rawX = (event.getX() - getWidth() / 2f) / dp(34);
                    float rawY = (getHeight() / 2f - event.getY()) / dp(34);
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

    private static float axis(MotionEvent event, int axis) {
        InputDevice device = event.getDevice();
        InputDevice.MotionRange range = device == null ? null : device.getMotionRange(axis, event.getSource());
        float value = event.getAxisValue(axis);
        return range == null || Math.abs(value) <= Math.max(range.getFlat(), 0.07f) ? 0 :
            Math.max(-1f, Math.min(1f, value));
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (player == null || !event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.getActionMasked() != MotionEvent.ACTION_MOVE)
            return super.onGenericMotionEvent(event);
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
        else if (!search.isEmpty() && games != null && !loadingGames) showLibraryAndClearSearch();
        else super.onBackPressed();
    }

    private void showLibraryAndClearSearch() {
        search = "";
        showLibrary(games);
        loadGames("");
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
        if (player != null) stopPlaybackAsync();
        super.onDestroy();
    }
}
