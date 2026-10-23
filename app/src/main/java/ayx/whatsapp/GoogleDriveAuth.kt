package ayx.whatsapp

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.GooglePlayServicesAvailabilityException
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import java.io.IOException

/**
 * WhatsApp-style Google account plumbing for Drive Backup (v171).
 *
 * WHY play-services-auth (GoogleSignIn) and not Credential Manager:
 * Credential Manager's "Sign in with Google" needs a *web* OAuth client
 * ID baked into the app — none exists for AyX and none can be created
 * from here. GoogleSignIn needs no client ID in code at all: it resolves
 * the app's Android OAuth client from package name + signing SHA-1, so
 * the only setup is the one-time Google Cloud registration, and until
 * that exists sign-in simply fails with DEVELOPER_ERROR, which the UI
 * turns into a calm "setup is still pending" line (see below).
 *
 * The Drive scope is requested at sign-in time (drive.file: only files
 * this app creates — it can never see the rest of the user's Drive).
 * Access tokens come from GoogleAuthUtil per operation and are NEVER
 * persisted; the token cache is Google's own.
 */
object GoogleDriveAuth {
    const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

    // Ready-to-show user messages (the section/status line displays these
    // verbatim — sign-in problems must never surface as raw exceptions).
    const val MSG_NO_PLAY_SERVICES =
        "Google Play services isn't available on this phone, so Drive backup can't sign in here."
    const val MSG_SETUP_PENDING =
        "Google sign-in setup is still pending for this app — finish the one-time Google Cloud steps, then try again."
    const val MSG_CANCELLED = "Sign-in was cancelled — nothing changed."
    const val MSG_NETWORK = "Network problem — check your connection and try again."
    const val MSG_SIGN_IN_AGAIN =
        "Google needs you to sign in again — tap “Google account” and allow Drive access."
    const val MSG_ADD_ACCOUNT = "Add your Google account first — tap “Google account”."

    /** A failure whose message is already user-readable. */
    class AuthException(message: String) : Exception(message)

    fun scope(): Scope = Scope(SCOPE_DRIVE_FILE)

    fun signInOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(scope())
            .build()

    fun client(ctx: Context): GoogleSignInClient =
        GoogleSignIn.getClient(ctx, signInOptions())

    /** The last signed-in account (survives restarts), or null. */
    fun signedInAccount(ctx: Context): GoogleSignInAccount? =
        runCatching { GoogleSignIn.getLastSignedInAccount(ctx) }.getOrNull()

    fun hasDrivePermission(ctx: Context): Boolean {
        val acc = signedInAccount(ctx) ?: return false
        return runCatching { GoogleSignIn.hasPermissions(acc, scope()) }.getOrDefault(false)
    }

    /** Null when Play services is usable; otherwise the user message. */
    fun playServicesProblem(ctx: Context): String? {
        val code = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx)
        }.getOrDefault(ConnectionResult.SERVICE_MISSING)
        return if (code == ConnectionResult.SUCCESS) null else MSG_NO_PLAY_SERVICES
    }

    /**
     * Map a sign-in Activity result to the signed-in email. Throws
     * [AuthException] with a calm, specific message for every failure
     * class (cancelled / not-configured / network / other).
     */
    fun accountFromResult(data: Intent?): String {
        val account = try {
            GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
        } catch (e: ApiException) {
            throw AuthException(
                when (e.statusCode) {
                    12501 -> MSG_CANCELLED // GoogleSignInStatusCodes.SIGN_IN_CANCELLED
                    10 -> MSG_SETUP_PENDING // CommonStatusCodes.DEVELOPER_ERROR
                    7 -> MSG_NETWORK // CommonStatusCodes.NETWORK_ERROR
                    else -> "Google sign-in failed — please try again."
                }
            )
        }
        return account.email
            ?: throw AuthException("That Google account didn't share an email address — pick another account.")
    }

    /**
     * Blocking Drive access token for the signed-in account — call on
     * IO only. Consent was granted at sign-in, so this is silent; a
     * revoked grant surfaces as [MSG_SIGN_IN_AGAIN], never an intent.
     */
    fun accessToken(ctx: Context): String {
        val account = signedInAccount(ctx) ?: throw AuthException(MSG_ADD_ACCOUNT)
        val acct = account.account ?: throw AuthException(MSG_SIGN_IN_AGAIN)
        return try {
            GoogleAuthUtil.getToken(ctx, acct, "oauth2:$SCOPE_DRIVE_FILE")
        } catch (e: GooglePlayServicesAvailabilityException) {
            throw AuthException(MSG_NO_PLAY_SERVICES)
        } catch (e: UserRecoverableAuthException) {
            // Consent was revoked (or never fully granted): the fix is a
            // fresh sign-in tap, which re-runs the consent screen.
            throw AuthException(MSG_SIGN_IN_AGAIN)
        } catch (e: GoogleAuthException) {
            throw AuthException(MSG_SIGN_IN_AGAIN)
        } catch (e: IOException) {
            throw AuthException(MSG_NETWORK)
        }
    }

    /** Drop a cached token Google now rejects (used once on a 401). */
    fun invalidateToken(ctx: Context, token: String) {
        runCatching { GoogleAuthUtil.invalidateToken(ctx, token) }
    }

    fun signOut(ctx: Context, onDone: () -> Unit) {
        runCatching {
            client(ctx).signOut().addOnCompleteListener { onDone() }
        }.onFailure { onDone() }
    }
}
