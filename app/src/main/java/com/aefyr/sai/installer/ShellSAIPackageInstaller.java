package com.aefyr.sai.installer;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.util.Pair;

import com.aefyr.sai.BuildConfig;
import com.aefyr.sai.R;
import com.aefyr.sai.model.apksource.ApkSource;
import com.aefyr.sai.shell.Shell;
import com.aefyr.sai.utils.Logs;
import com.aefyr.sai.utils.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Base class for installers that install packages via pm shell command, child classes must provide a Shell{@link com.aefyr.sai.shell.Shell}
 * Please note, that it's unsafe to use multiple ShellPackageInstaller instances at the same time because installation completion is determined by the ACTION_PACKAGE_ADDED broadcast
 */
public abstract class ShellSAIPackageInstaller extends SAIPackageInstaller {
    private static final String TAG = "ShellSAIPI";

    private AtomicBoolean mIsAwaitingBroadcast = new AtomicBoolean(false);

    private BroadcastReceiver mPackageInstalledBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, intent.toString());
            if (!mIsAwaitingBroadcast.get())
                return;

            String installedPackage = "null";
            try {
                installedPackage = intent.getDataString().replace("package:", "");
                String installerPackage = getContext().getPackageManager().getInstallerPackageName(installedPackage);
                Log.d(TAG, "installerPackage=" + installerPackage);
                if (!installerPackage.equals(BuildConfig.APPLICATION_ID))
                    return;
            } catch (Exception e) {
                Logs.logException(e);
                Log.wtf(TAG, e);
            }

            mIsAwaitingBroadcast.set(false);
            dispatchCurrentSessionUpdate(InstallationStatus.INSTALLATION_SUCCEED, installedPackage);
            installationCompleted();
        }
    };

    protected ShellSAIPackageInstaller(Context c) {
        super(c);
        IntentFilter packageAddedFilter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        packageAddedFilter.addDataScheme("package");
        getContext().registerReceiver(mPackageInstalledBroadcastReceiver, packageAddedFilter);
    }

    @SuppressLint("DefaultLocale")
    @Override
    protected void installApkFiles(ApkSource aApkSource) {
        try (ApkSource apkSource = aApkSource) {

            if (!getShell().isAvailable()) {
                dispatchCurrentSessionUpdate(InstallationStatus.INSTALLATION_FAILED, getContext().getString(R.string.installer_error_shell, getInstallerName(), getShellUnavailableMessage()));
                installationCompleted();
                return;
            }

            int sessionId = createSession();

            int currentApkFile = 0;
            while (apkSource.nextApk())
                ensureCommandSucceeded(getShell().exec(new Shell.Command("pm", "install-write", "-S", String.valueOf(apkSource.getApkLength()), String.valueOf(sessionId), String.format("%d.apk", currentApkFile++)), apkSource.openApkInputStream()));

            mIsAwaitingBroadcast.set(true);
            Shell.Result installationResult = getShell().exec(new Shell.Command("pm", "install-commit", String.valueOf(sessionId)));
            if (!installationResult.isSuccessful()) {
                mIsAwaitingBroadcast.set(false);
                dispatchCurrentSessionUpdate(InstallationStatus.INSTALLATION_FAILED, getContext().getString(R.string.installer_error_shell, getInstallerName(), getSessionInfo(apkSource) + "\n\n" + installationResult.toString()));
                installationCompleted();
            }
        } catch (Exception e) {
            Log.w(TAG, e);
            dispatchCurrentSessionUpdate(InstallationStatus.INSTALLATION_FAILED, getContext().getString(R.string.installer_error_shell, getInstallerName(), getSessionInfo(aApkSource) + "\n\n" + Utils.throwableToString(e)));
            installationCompleted();
        }
    }

    private String ensureCommandSucceeded(Shell.Result result) {
        if (!result.isSuccessful())
            throw new RuntimeException(result.toString());
        return result.out;
    }

    private String getSessionInfo(ApkSource apkSource) {
        String saiVersion = "???";
        try {
            saiVersion = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            Log.wtf(TAG, "Unable to get SAI version", e);
        }
        return String.format("%s: %s %s | %s | Android %s | Using %s ApkSource implementation | SAI %s", getContext().getString(R.string.installer_device), Build.BRAND, Build.MODEL, Utils.isMiui() ? "MIUI" : "Not MIUI", Build.VERSION.RELEASE, apkSource.getClass().getSimpleName(), saiVersion);
    }

    private int createSession() throws RuntimeException {
        ArrayList<Shell.Command> commandsToAttempt = new ArrayList<>();
        commandsToAttempt.add(new Shell.Command("pm", "install-create", "-r", "--install-location", "0", "-i", getShell().makeLiteral(BuildConfig.APPLICATION_ID)));
        commandsToAttempt.add(new Shell.Command("pm", "install-create", "-r", "-i", getShell().makeLiteral(BuildConfig.APPLICATION_ID)));

        List<Pair<Shell.Command, String>> attemptedCommands = new ArrayList<>();

        for (Shell.Command commandToAttempt : commandsToAttempt) {
            Shell.Result result = getShell().exec(commandToAttempt);
            attemptedCommands.add(new Pair<>(commandToAttempt, result.toString()));

            if (!result.isSuccessful()) {
                Log.w(TAG, String.format("Command failed: %s > %s", commandToAttempt, result));
                continue;
            }

            Integer sessionId = extractSessionId(result.out);
            if (sessionId != null)
                return sessionId;
            else
                Log.w(TAG, String.format("Command failed: %s > %s", commandToAttempt, result));
        }

        StringBuilder exceptionMessage = new StringBuilder("Unable to create session, attempted commands: ");
        int i = 1;
        for (Pair<Shell.Command, String> attemptedCommand : attemptedCommands) {
            exceptionMessage.append("\n\n").append(i++).append(") ==========================\n")
                    .append(attemptedCommand.first)
                    .append("\nVVVVVVVVVVVVVVVV\n")
                    .append(attemptedCommand.second);
        }
        exceptionMessage.append("\n");

        throw new IllegalStateException(exceptionMessage.toString());
    }

    private Integer extractSessionId(String commandResult) {
        try {
            Pattern sessionIdPattern = Pattern.compile("(\\d+)");
            Matcher sessionIdMatcher = sessionIdPattern.matcher(commandResult);
            sessionIdMatcher.find();
            return Integer.parseInt(sessionIdMatcher.group(1));
        } catch (Exception e) {
            Log.w(TAG, commandResult, e);
            return null;
        }
    }

    protected abstract Shell getShell();

    protected abstract String getInstallerName();

    protected abstract String getShellUnavailableMessage();
}
