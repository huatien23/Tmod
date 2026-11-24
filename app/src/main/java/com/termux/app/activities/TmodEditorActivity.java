package com.termux.app.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.termux.app.TermuxService;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.dingyi222666.monarch.language.Language;
import io.github.dingyi222666.monarch.types.IMonarchLanguage;
import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.langs.monarch.MonarchLanguage;
import io.github.rosemoe.sora.langs.monarch.registry.model.GrammarDefinition;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.EditorSearcher;
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula;
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub;

/** Acode-style project editor backed by Tmod's own terminal environment. */
public final class TmodEditorActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "com.huatiencode.acode.PATH";
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final String PREFS = "tmod_editor";

    private final Map<String, Document> mDocuments = new LinkedHashMap<>();
    private final List<File> mEntries = new ArrayList<>();
    private DrawerLayout mDrawer;
    private LinearLayout mTabs;
    private LinearLayout mSearchPanel;
    private ListView mFiles;
    private TextView mProjectPath;
    private TextView mStatus;
    private EditText mFind;
    private EditText mReplace;
    private CheckBox mMatchCase;
    private CodeEditor mEditor;
    private File mDirectory;
    private Document mCurrent;
    private SharedPreferences mPrefs;
    private boolean mLoading;
    private boolean mWrap;
    private boolean mDark;
    private float mTextSize;

    private static final class Document {
        String key;
        File file;
        String name;
        String text;
        int line;
        int column;
        boolean dirty;

        Document(File file, String text) {
            this.file = file;
            this.key = file.getAbsolutePath();
            this.name = file.getName();
            this.text = text;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        mPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        mWrap = mPrefs.getBoolean("wrap", false);
        mDark = mPrefs.getBoolean("dark", true);
        mTextSize = mPrefs.getFloat("text_size", 15f);
        createUi();

        File home = TermuxConstants.TERMUX_HOME_DIR;
        if (!home.isDirectory() && !home.mkdirs()) {
            toast(home.getAbsolutePath());
            finish();
            return;
        }
        mDirectory = new File(mPrefs.getString("project", home.getAbsolutePath()));
        if (!mDirectory.isDirectory()) mDirectory = home;
        String requested = getIntent().getStringExtra(EXTRA_PATH);
        if (requested != null) {
            File target = new File(requested);
            if (target.isDirectory()) mDirectory = target;
            else if (target.isFile()) {
                if (target.getParentFile() != null) mDirectory = target.getParentFile();
                openFile(target);
            }
        }
        refreshFiles();
        if (mCurrent == null) showWelcome();
    }

    private void createUi() {
        mDrawer = new DrawerLayout(this);
        setContentView(mDrawer);
        LinearLayout main = vertical();
        main.setBackgroundColor(Color.rgb(30, 30, 30));
        mDrawer.addView(main, new DrawerLayout.LayoutParams(-1, -1));

        LinearLayout toolbar = horizontal();
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(Color.rgb(42, 45, 48));
        toolbar.addView(button("☰", v -> mDrawer.openDrawer(Gravity.START)));
        toolbar.addView(button("New", v -> askNew(false)));
        toolbar.addView(button("Save", v -> saveCurrent()));
        toolbar.addView(button("Find", v -> toggleSearch(true)));
        toolbar.addView(button("Run", v -> runCurrent()));
        toolbar.addView(button("Web", v -> previewCurrent()));
        toolbar.addView(button("⋮", v -> showEditorMenu()));
        main.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(48)));

        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        mTabs = horizontal();
        tabScroll.addView(mTabs);
        main.addView(tabScroll, new LinearLayout.LayoutParams(-1, dp(42)));
        createSearchPanel();
        main.addView(mSearchPanel, new LinearLayout.LayoutParams(-1, -2));

        mEditor = new CodeEditor(this);
        mEditor.setTextSize(mTextSize);
        mEditor.setWordwrap(mWrap);
        mEditor.setTabWidth(4);
        mEditor.setHighlightCurrentLine(true);
        mEditor.setHighlightBracketPair(true);
        mEditor.setColorScheme(mDark ? new SchemeDarcula() : new SchemeGitHub());
        mEditor.subscribeEvent(ContentChangeEvent.class, (event, unsubscribe) -> {
            if (!mLoading && mCurrent != null) {
                mCurrent.dirty = true;
                mCurrent.text = mEditor.getText().toString();
                updateTabs();
                updateStatus();
            }
        });
        main.addView(mEditor, new LinearLayout.LayoutParams(-1, 0, 1));
        main.addView(createSymbolBar(), new LinearLayout.LayoutParams(-1, dp(42)));

        mStatus = new TextView(this);
        mStatus.setTextColor(Color.LTGRAY);
        mStatus.setTextSize(12);
        mStatus.setSingleLine(true);
        mStatus.setPadding(dp(8), 0, dp(8), 0);
        mStatus.setGravity(Gravity.CENTER_VERTICAL);
        mStatus.setBackgroundColor(Color.rgb(38, 41, 44));
        main.addView(mStatus, new LinearLayout.LayoutParams(-1, dp(28)));
        createProjectDrawer();
    }

    private void createSearchPanel() {
        mSearchPanel = horizontal();
        mSearchPanel.setGravity(Gravity.CENTER_VERTICAL);
        mSearchPanel.setBackgroundColor(Color.rgb(53, 56, 60));
        mSearchPanel.setVisibility(View.GONE);
        mFind = input("Find");
        mReplace = input("Replace");
        mMatchCase = new CheckBox(this);
        mMatchCase.setText("Aa");
        mMatchCase.setTextColor(Color.WHITE);
        mSearchPanel.addView(mFind, new LinearLayout.LayoutParams(0, dp(40), 1));
        mSearchPanel.addView(mReplace, new LinearLayout.LayoutParams(0, dp(40), 1));
        mSearchPanel.addView(mMatchCase);
        mSearchPanel.addView(button("‹", v -> find(false)));
        mSearchPanel.addView(button("›", v -> find(true)));
        mSearchPanel.addView(button("R", v -> replaceOne()));
        mSearchPanel.addView(button("All", v -> replaceAll()));
        mSearchPanel.addView(button("×", v -> toggleSearch(false)));
        mFind.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { startSearch(); }
            public void afterTextChanged(Editable s) {}
        });
    }

    private void createProjectDrawer() {
        LinearLayout panel = vertical();
        panel.setPadding(dp(8), dp(12), dp(8), dp(8));
        panel.setBackgroundColor(Color.rgb(35, 38, 42));
        DrawerLayout.LayoutParams params = new DrawerLayout.LayoutParams(
            Math.min(dp(330), getResources().getDisplayMetrics().widthPixels * 5 / 6), -1);
        params.gravity = Gravity.START;
        mDrawer.addView(panel, params);
        TextView title = label("TMOD PROJECT", 18);
        panel.addView(title, new LinearLayout.LayoutParams(-1, dp(42)));
        mProjectPath = label("", 12);
        panel.addView(mProjectPath, new LinearLayout.LayoutParams(-1, dp(46)));
        LinearLayout actions = horizontal();
        actions.addView(button("Home", v -> changeDirectory(TermuxConstants.TERMUX_HOME_DIR)));
        actions.addView(button("Up", v -> goUp()));
        actions.addView(button("File+", v -> askNew(false)));
        actions.addView(button("Dir+", v -> askNew(true)));
        actions.addView(button("↻", v -> refreshFiles()));
        panel.addView(actions, new LinearLayout.LayoutParams(-1, dp(46)));
        mFiles = new ListView(this);
        mFiles.setOnItemClickListener((parent, view, position, id) -> {
            File file = mEntries.get(position);
            if (file.isDirectory()) changeDirectory(file); else openFile(file);
        });
        mFiles.setOnItemLongClickListener((parent, view, position, id) -> {
            showFileMenu(mEntries.get(position));
            return true;
        });
        panel.addView(mFiles, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private HorizontalScrollView createSymbolBar() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setBackgroundColor(Color.rgb(45, 48, 52));
        LinearLayout row = horizontal();
        String[] symbols = {"TAB", "ESC", "{", "}", "(", ")", "[", "]", "<", ">", "=", ";", ":", "\"", "'", "|", "&", "/", "\\", "_", "-", "+"};
        for (String symbol : symbols) row.addView(button(symbol, v -> insertSymbol(symbol)));
        scroll.addView(row);
        return scroll;
    }

    private void insertSymbol(String value) {
        if ("TAB".equals(value)) mEditor.insertText("    ", 4);
        else if ("ESC".equals(value)) mEditor.hideSoftInput();
        else mEditor.insertText(value, value.length());
    }

    private void refreshFiles() {
        if (mDirectory == null || !mDirectory.isDirectory()) mDirectory = TermuxConstants.TERMUX_HOME_DIR;
        mPrefs.edit().putString("project", mDirectory.getAbsolutePath()).apply();
        mProjectPath.setText(mDirectory.getAbsolutePath());
        File[] files = mDirectory.listFiles();
        if (files == null) files = new File[0];
        Arrays.sort(files, (a, b) -> a.isDirectory() == b.isDirectory()
            ? a.getName().compareToIgnoreCase(b.getName()) : (a.isDirectory() ? -1 : 1));
        mEntries.clear();
        mEntries.addAll(Arrays.asList(files));
        List<String> names = new ArrayList<>();
        for (File file : files) names.add((file.isDirectory() ? "▸ " : "   ") + file.getName());
        mFiles.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
            @NonNull public View getView(int p, View view, @NonNull ViewGroup parent) {
                TextView text = (TextView) super.getView(p, view, parent);
                text.setTextColor(Color.WHITE);
                return text;
            }
        });
    }

    private void changeDirectory(File file) {
        if (file != null && file.isDirectory()) { mDirectory = file; refreshFiles(); }
    }

    private void goUp() {
        if (mDirectory != null && mDirectory.getParentFile() != null) changeDirectory(mDirectory.getParentFile());
    }

    private void openFile(File file) {
        Document old = mDocuments.get(file.getAbsolutePath());
        if (old != null) { switchTo(old); return; }
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = readLimited(input, file.length());
            for (byte value : bytes) if (value == 0) throw new IOException("Binary files cannot be edited");
            Document document = new Document(file, new String(bytes, StandardCharsets.UTF_8));
            mDocuments.put(document.key, document);
            switchTo(document);
            mDrawer.closeDrawer(Gravity.START);
        } catch (IOException e) { toast(message(e)); }
    }

    private void switchTo(Document document) {
        stashCurrent();
        mCurrent = document;
        mEditor.setEditable(true);
        mLoading = true;
        mEditor.setEditorLanguage(languageFor(document.name));
        mEditor.setText(document.text);
        int line = Math.max(0, Math.min(document.line, mEditor.getLineCount() - 1));
        int column = Math.max(0, Math.min(document.column, mEditor.getText().getColumnCount(line)));
        mEditor.setSelection(line, column);
        mLoading = false;
        setTitle("Tmod · " + document.name);
        updateTabs();
        updateStatus();
    }

    private void stashCurrent() {
        if (mCurrent == null) return;
        mCurrent.text = mEditor.getText().toString();
        mCurrent.line = mEditor.getCursor().getLeftLine();
        mCurrent.column = mEditor.getCursor().getLeftColumn();
    }

    private void updateTabs() {
        mTabs.removeAllViews();
        for (Document document : mDocuments.values()) {
            TextView tab = label((document.dirty ? "● " : "") + document.name + "  ×", 14);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(12), 0, dp(12), 0);
            tab.setBackgroundColor(document == mCurrent ? Color.rgb(70, 76, 82) : Color.rgb(45, 48, 52));
            tab.setOnClickListener(v -> switchTo(document));
            tab.setOnLongClickListener(v -> { closeDocument(document); return true; });
            mTabs.addView(tab, new LinearLayout.LayoutParams(-2, -1));
        }
    }

    private void showWelcome() {
        mCurrent = null;
        mLoading = true;
        mEditor.setEditorLanguage(new EmptyLanguage());
        mEditor.setText("# Tmod Editor\n\nOpen the project drawer or create a file.\n\nCtrl+S Save · Ctrl+F Find · Ctrl+W Close · Ctrl+Z/Y Undo/Redo\n");
        mEditor.setEditable(false);
        mLoading = false;
        setTitle("Tmod Editor");
        updateStatus();
    }

    private void askNew(boolean folder) {
        EditText name = input(folder ? "Folder name" : "File name");
        new AlertDialog.Builder(this).setTitle(folder ? "New folder" : "New file").setView(name)
            .setPositiveButton("Create", (dialog, which) -> {
                String value = name.getText().toString().trim();
                if (!validName(value)) { toast("Invalid name"); return; }
                File target = new File(mDirectory, value);
                try {
                    boolean made = folder ? target.mkdir() : target.createNewFile();
                    if (!made && !target.exists()) throw new IOException("Create failed");
                    refreshFiles();
                    if (!folder) openFile(target);
                } catch (IOException e) { toast(message(e)); }
            }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private boolean saveCurrent() {
        stashCurrent();
        if (mCurrent == null) return false;
        try (FileOutputStream output = new FileOutputStream(mCurrent.file)) {
            output.write(mCurrent.text.getBytes(StandardCharsets.UTF_8));
            mCurrent.dirty = false;
            updateTabs();
            updateStatus();
            toast("Saved " + mCurrent.name);
            return true;
        } catch (IOException e) { toast(message(e)); return false; }
    }

    private void saveAll() {
        Document selected = mCurrent;
        List<Document> dirty = new ArrayList<>();
        stashCurrent();
        for (Document document : mDocuments.values()) if (document.dirty) dirty.add(document);
        for (Document document : dirty) { mCurrent = document; saveCurrent(); }
        if (selected != null) switchTo(selected);
    }

    private void closeDocument(Document document) {
        stashCurrent();
        if (!document.dirty) { removeDocument(document); return; }
        new AlertDialog.Builder(this).setTitle("Unsaved changes").setMessage(document.name)
            .setPositiveButton("Save", (d, w) -> {
                Document old = mCurrent; mCurrent = document;
                if (saveCurrent()) removeDocument(document); else mCurrent = old;
            }).setNegativeButton("Discard", (d, w) -> removeDocument(document))
            .setNeutralButton(android.R.string.cancel, null).show();
    }

    private void removeDocument(Document document) {
        mDocuments.remove(document.key);
        if (document == mCurrent) {
            mCurrent = null;
            Document next = null;
            for (Document item : mDocuments.values()) next = item;
            if (next == null) showWelcome(); else switchTo(next);
        }
        updateTabs();
    }

    private void showFileMenu(File file) {
        new AlertDialog.Builder(this).setTitle(file.getName())
            .setItems(new String[]{"Rename", "Delete"}, (dialog, which) -> {
                if (which == 0) renameFile(file); else deleteFile(file);
            }).show();
    }

    private void renameFile(File file) {
        EditText name = input("New name"); name.setText(file.getName()); name.selectAll();
        new AlertDialog.Builder(this).setTitle("Rename").setView(name)
            .setPositiveButton("Rename", (dialog, which) -> {
                String value = name.getText().toString().trim();
                if (!validName(value)) { toast("Invalid name"); return; }
                File target = new File(file.getParentFile(), value);
                if (!file.renameTo(target)) { toast("Rename failed"); return; }
                Document document = mDocuments.remove(file.getAbsolutePath());
                if (document != null) {
                    document.file = target; document.key = target.getAbsolutePath(); document.name = target.getName();
                    mDocuments.put(document.key, document);
                }
                refreshFiles(); updateTabs();
            }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void deleteFile(File file) {
        new AlertDialog.Builder(this).setTitle("Delete").setMessage(file.getAbsolutePath())
            .setPositiveButton("Delete", (dialog, which) -> {
                String[] children = file.isDirectory() ? file.list() : null;
                if (children != null && children.length > 0) { toast("Only empty folders can be deleted"); return; }
                if (!file.delete()) { toast("Delete failed"); return; }
                Document document = mDocuments.remove(file.getAbsolutePath());
                if (document == mCurrent) showWelcome();
                refreshFiles(); updateTabs();
            }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void toggleSearch(boolean show) {
        mSearchPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) { mFind.requestFocus(); startSearch(); }
        else { mEditor.getSearcher().stopSearch(); mEditor.requestFocus(); }
    }

    private void startSearch() {
        String query = mFind.getText().toString();
        if (query.isEmpty()) { mEditor.getSearcher().stopSearch(); return; }
        mEditor.getSearcher().search(query, new EditorSearcher.SearchOptions(
            EditorSearcher.SearchOptions.TYPE_NORMAL, !mMatchCase.isChecked()));
    }

    private void find(boolean next) {
        startSearch();
        if (!(next ? mEditor.getSearcher().gotoNext() : mEditor.getSearcher().gotoPrevious())) toast("No match");
    }

    private void replaceOne() {
        startSearch();
        if (!mEditor.getSearcher().isMatchedPositionSelected()) mEditor.getSearcher().gotoNext();
        if (mEditor.getSearcher().isMatchedPositionSelected())
            mEditor.getSearcher().replaceCurrentMatch(mReplace.getText().toString());
    }

    private void replaceAll() {
        startSearch();
        if (mEditor.getSearcher().hasQuery()) mEditor.getSearcher().replaceAll(mReplace.getText().toString());
    }

    private void showEditorMenu() {
        String[] choices = {"Save all", "Undo", "Redo", "Close tab", "Toggle word wrap",
            "Font larger", "Font smaller", "Toggle dark/light", "Go to line", "Project drawer"};
        new AlertDialog.Builder(this).setTitle("Editor").setItems(choices, (dialog, which) -> {
            switch (which) {
                case 0: saveAll(); break;
                case 1: mEditor.undo(); break;
                case 2: mEditor.redo(); break;
                case 3: if (mCurrent != null) closeDocument(mCurrent); break;
                case 4: mWrap = !mWrap; mEditor.setWordwrap(mWrap); mPrefs.edit().putBoolean("wrap", mWrap).apply(); break;
                case 5: changeTextSize(1); break;
                case 6: changeTextSize(-1); break;
                case 7: mDark = !mDark; mEditor.setColorScheme(mDark ? new SchemeDarcula() : new SchemeGitHub()); mPrefs.edit().putBoolean("dark", mDark).apply(); break;
                case 8: goToLine(); break;
                case 9: mDrawer.openDrawer(Gravity.START); break;
            }
            updateStatus();
        }).show();
    }

    private void changeTextSize(int delta) {
        mTextSize = Math.max(9, Math.min(32, mTextSize + delta));
        mEditor.setTextSize(mTextSize);
        mPrefs.edit().putFloat("text_size", mTextSize).apply();
    }

    private void goToLine() {
        EditText line = input("Line number"); line.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this).setTitle("Go to line").setView(line)
            .setPositiveButton("Go", (d, w) -> {
                try { mEditor.jumpToLine(Math.max(0, Integer.parseInt(line.getText().toString()) - 1)); }
                catch (NumberFormatException ignored) {}
            }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void updateStatus() {
        if (mStatus == null) return;
        if (mCurrent == null) { mStatus.setText("Tmod editor · terminal and packages unchanged"); return; }
        mStatus.setText(mCurrent.file.getAbsolutePath() + " · Ln " + (mEditor.getCursor().getLeftLine() + 1)
            + ", Col " + (mEditor.getCursor().getLeftColumn() + 1) + (mWrap ? " · Wrap" : ""));
    }

    private void previewCurrent() {
        stashCurrent();
        if (mCurrent == null) return;
        String lower = mCurrent.name.toLowerCase(Locale.US);
        if (!lower.endsWith(".html") && !lower.endsWith(".htm") && !lower.endsWith(".svg") && !lower.endsWith(".md")) {
            toast("Preview supports HTML, SVG and Markdown"); return;
        }
        Intent intent = new Intent(this, TmodPreviewActivity.class);
        intent.putExtra(TmodPreviewActivity.EXTRA_TITLE, mCurrent.name);
        intent.putExtra(TmodPreviewActivity.EXTRA_CONTENT, mCurrent.text);
        intent.putExtra(TmodPreviewActivity.EXTRA_BASE_URL, Uri.fromFile(mCurrent.file.getParentFile()).toString() + "/");
        intent.putExtra(TmodPreviewActivity.EXTRA_MARKDOWN, lower.endsWith(".md"));
        startActivity(intent);
    }

    private void runCurrent() {
        stashCurrent();
        if (mCurrent == null) return;
        if (mCurrent.dirty && !saveCurrent()) return;
        String command = commandFor(mCurrent.file);
        if (command == null) return;
        String bash = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        String wrapped = command + "; tmod_status=$?; printf '\\n[Process exited %s]\\n' \"$tmod_status\"; exec " + quote(bash);
        Uri uri = new Uri.Builder().scheme(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE).path(bash).build();
        Intent intent = new Intent(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, uri);
        intent.setClass(this, TermuxService.class);
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_ARGUMENTS, new String[]{"-lc", wrapped});
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_WORKDIR, mCurrent.file.getParent());
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_RUNNER, ExecutionCommand.Runner.TERMINAL_SESSION.getName());
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_SESSION_ACTION, "0");
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_SHELL_NAME, "Tmod Run: " + mCurrent.name);
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_SHELL_CREATE_MODE, ExecutionCommand.ShellCreateMode.ALWAYS.getMode());
        if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(this, intent); else startService(intent);
    }

    private String commandFor(File file) {
        String lower = file.getName().toLowerCase(Locale.US);
        String path = quote(file.getAbsolutePath());
        String base = file.getName().replaceFirst("\\.[^.]+$", "");
        String out = quote(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH + "/tmod-" + base);
        if (lower.endsWith(".py")) return "python " + path;
        if (lower.matches(".*\\.(js|mjs|cjs)$")) return "node " + path;
        if (lower.endsWith(".ts")) return "npx ts-node " + path;
        if (lower.matches(".*\\.(sh|bash|zsh)$")) return "bash " + path;
        if (lower.endsWith(".php")) return "php " + path;
        if (lower.endsWith(".rb")) return "ruby " + path;
        if (lower.endsWith(".lua")) return "lua " + path;
        if (lower.endsWith(".pl")) return "perl " + path;
        if (lower.endsWith(".go")) return "go run " + path;
        if (lower.endsWith(".java")) return "javac " + path + " && java -cp " + quote(file.getParent()) + " " + quote(base);
        if (lower.endsWith(".c")) return "clang " + path + " -o " + out + " && " + out;
        if (lower.matches(".*\\.(cc|cpp|cxx)$")) return "clang++ " + path + " -o " + out + " && " + out;
        if (lower.endsWith(".rs")) return "rustc " + path + " -o " + out + " && " + out;
        if (lower.matches(".*\\.(html|htm)$")) { previewCurrent(); return null; }
        if (file.canExecute()) return path;
        toast("No runner for this file type");
        return null;
    }

    private io.github.rosemoe.sora.lang.Language languageFor(String filename) {
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.US) : "";
        String[] spec;
        switch (ext) {
            case "py": spec = new String[]{"Python", "python"}; break;
            case "js": case "mjs": case "cjs": spec = new String[]{"Javascript", "javascript"}; break;
            case "ts": case "tsx": spec = new String[]{"Typescript", "typescript"}; break;
            case "java": spec = new String[]{"Java", "java"}; break;
            case "kt": case "kts": spec = new String[]{"Kotlin", "kotlin"}; break;
            case "c": case "h": case "cc": case "cpp": case "cxx": case "hpp": spec = new String[]{"Cpp", "cpp"}; break;
            case "html": case "htm": spec = new String[]{"Html", "html"}; break;
            case "css": case "scss": spec = new String[]{"Css", "css"}; break;
            case "md": spec = new String[]{"Markdown", "markdown"}; break;
            case "sh": case "bash": case "zsh": spec = new String[]{"Shell", "shell"}; break;
            case "json": spec = new String[]{"Json", "json"}; break;
            case "xml": case "svg": spec = new String[]{"Xml", "xml"}; break;
            case "yaml": case "yml": spec = new String[]{"Yaml", "yaml"}; break;
            case "php": spec = new String[]{"Php", "php"}; break;
            case "go": spec = new String[]{"Go", "go"}; break;
            case "rs": spec = new String[]{"Rust", "rust"}; break;
            case "sql": spec = new String[]{"Sql", "sql"}; break;
            default: return new EmptyLanguage();
        }
        try {
            Class<?> holder = Class.forName("io.github.dingyi222666.monarch.languages.Language" + spec[0] + "Kt");
            Method getter = holder.getMethod("get" + spec[0] + "Language");
            IMonarchLanguage syntax = (IMonarchLanguage) getter.invoke(null);
            Language grammar = new Language(spec[1], syntax, spec[1], Collections.singletonList("." + ext), Collections.emptyMap());
            GrammarDefinition<Language> definition = new GrammarDefinition<>(spec[1], grammar, Collections.emptyMap(), null, "source." + spec[1]);
            return MonarchLanguage.create(definition, true);
        } catch (Exception ignored) { return new EmptyLanguage(); }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.isCtrlPressed()) {
            if (keyCode == KeyEvent.KEYCODE_S) { saveCurrent(); return true; }
            if (keyCode == KeyEvent.KEYCODE_F) { toggleSearch(true); return true; }
            if (keyCode == KeyEvent.KEYCODE_W) { if (mCurrent != null) closeDocument(mCurrent); return true; }
            if (keyCode == KeyEvent.KEYCODE_Z) { mEditor.undo(); return true; }
            if (keyCode == KeyEvent.KEYCODE_Y) { mEditor.redo(); return true; }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onBackPressed() {
        if (mDrawer.isDrawerOpen(Gravity.START)) { mDrawer.closeDrawer(Gravity.START); return; }
        if (mSearchPanel.getVisibility() == View.VISIBLE) { toggleSearch(false); return; }
        stashCurrent();
        int dirty = 0; for (Document document : mDocuments.values()) if (document.dirty) dirty++;
        if (dirty == 0) { super.onBackPressed(); return; }
        new AlertDialog.Builder(this).setTitle("Unsaved files").setMessage(dirty + " file(s) have changes")
            .setPositiveButton("Save all", (d, w) -> { saveAll(); finish(); })
            .setNegativeButton("Discard", (d, w) -> finish()).setNeutralButton(android.R.string.cancel, null).show();
    }

    @Override protected void onDestroy() {
        if (mEditor != null) mEditor.release();
        super.onDestroy();
    }

    private LinearLayout vertical() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout horizontal() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); return v; }
    private Button button(String text, View.OnClickListener click) {
        Button b = new Button(this); b.setText(text); b.setTextSize(12); b.setTextColor(Color.WHITE); b.setAllCaps(false);
        b.setMinWidth(dp(44)); b.setMinimumWidth(dp(44)); b.setPadding(dp(7), 0, dp(7), 0); b.setOnClickListener(click); return b;
    }
    private TextView label(String text, int size) { TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(Color.WHITE); v.setGravity(Gravity.CENTER_VERTICAL); v.setPadding(dp(6), 0, dp(6), 0); return v; }
    private EditText input(String hint) { EditText e = new EditText(this); e.setHint(hint); e.setHintTextColor(Color.GRAY); e.setTextColor(Color.WHITE); e.setSingleLine(true); e.setPadding(dp(8), 0, dp(8), 0); return e; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    private static boolean validName(String s) { return s != null && !s.isEmpty() && !s.equals(".") && !s.equals("..") && !s.contains("/") && !s.contains("\\"); }
    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    private static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private static byte[] readLimited(InputStream input, long length) throws IOException {
        if (length > MAX_BYTES) throw new IOException("File is larger than 16 MiB");
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int read; int total = 0;
        while ((read = input.read(buffer)) != -1) { total += read; if (total > MAX_BYTES) throw new IOException("File is larger than 16 MiB"); output.write(buffer, 0, read); }
        return output.toByteArray();
    }
}
