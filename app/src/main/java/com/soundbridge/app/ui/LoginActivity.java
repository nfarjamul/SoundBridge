package com.soundbridge.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.soundbridge.app.data.AppPreferences;
import com.soundbridge.app.databinding.ActivityLoginBinding;
import com.soundbridge.app.firebase.FirebaseManager;

/**
 * Onboarding and sign-in screen.
 * Supports one-tap Google Sign-In backed by Firebase Authentication as well as instant
 * account-free local usage, plus immediate Light/Dark theme switching.
 */
public class LoginActivity extends AppCompatActivity {

    private ActivityLoginBinding binding;
    private FirebaseManager firebaseManager;
    private AppPreferences prefs;

    private final ActivityResultLauncher<Intent> googleSignInLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                setLoading(false);
                Intent data = result.getData();
                if (result.getResultCode() == Activity.RESULT_CANCELED && data == null) {
                    return;
                }
                if (data == null) {
                    return;
                }
                Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
                try {
                    GoogleSignInAccount account = task.getResult(ApiException.class);
                    if (account != null && !TextUtils.isEmpty(account.getIdToken())) {
                        authenticateWithFirebaseToken(account.getIdToken());
                    } else {
                        showError("Couldn't complete Google Sign-In. Please try again or continue without an account.");
                    }
                } catch (ApiException e) {
                    int code = e.getStatusCode();
                    if (code == 12501 || code == 12502) {
                        // User dismissed the account picker; return quietly
                        return;
                    }
                    if (code == 7) {
                        showError("No internet connection. Please check your network or continue without an account.");
                    } else {
                        showError("Unable to sign in with Google right now. You can try again or continue offline.");
                    }
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        firebaseManager = FirebaseManager.getInstance(this);
        prefs = new AppPreferences(this);

        if (firebaseManager.isSignedIn() || prefs.isLocalDemoMode()) {
            openMainActivity();
            return;
        }

        binding.btnToggleThemeLogin.setOnClickListener(v -> prefs.toggleDarkLightMode(this));
        binding.btnGoogleSignIn.setOnClickListener(v -> startGoogleSignIn());
        binding.btnContinueLocalDemo.setOnClickListener(v -> enterLocalMode());
    }

    private void startGoogleSignIn() {
        hideError();
        firebaseManager.detectFirebaseConfiguration();
        String webClientId = firebaseManager.getWebClientId();
        if (!firebaseManager.isFirebaseConfigured() || TextUtils.isEmpty(webClientId)) {
            showError("Cloud sign-in is currently unavailable. Please continue without an account.");
            return;
        }

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(webClientId)
                .requestEmail()
                .requestProfile()
                .build();

        GoogleSignInClient client = GoogleSignIn.getClient(this, gso);
        client.signOut().addOnCompleteListener(this, task -> {
            setLoading(true);
            googleSignInLauncher.launch(client.getSignInIntent());
        });
    }

    private void authenticateWithFirebaseToken(String idToken) {
        setLoading(true);
        firebaseManager.authenticateWithGoogleIdToken(idToken, new FirebaseManager.AuthCallback() {
            @Override
            public void onSuccess(String uid, String email, String displayName) {
                setLoading(false);
                String greetingName = !TextUtils.isEmpty(displayName) ? displayName : email;
                Toast.makeText(LoginActivity.this, "Welcome, " + greetingName, Toast.LENGTH_SHORT).show();
                openMainActivity();
            }

            @Override
            public void onError(String errorMessage) {
                setLoading(false);
                showError("Sign-in could not be completed. Please check your connection and try again.");
            }
        });
    }

    private void enterLocalMode() {
        prefs.setLocalDemoMode(true);
        openMainActivity();
    }

    private void openMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    private void setLoading(boolean loading) {
        binding.btnGoogleSignIn.setEnabled(!loading);
        binding.btnContinueLocalDemo.setEnabled(!loading);
    }

    private void showError(String message) {
        binding.tvAuthErrorStatus.setText(message);
        binding.tvAuthErrorStatus.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        binding.tvAuthErrorStatus.setVisibility(View.GONE);
    }
}
