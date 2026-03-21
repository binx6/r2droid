package top.wsdx233.r2droid.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

class DocumentsUiOpenDocumentTreeContract : ActivityResultContract<Uri?, Uri?>() {
    private val delegate = ActivityResultContracts.OpenDocumentTree()

    override fun createIntent(context: Context, input: Uri?): Intent {
        val intent = delegate.createIntent(context, input).apply {
            setPackage("com.android.documentsui")
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            intent.setPackage(null)
        }
        return intent
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return delegate.parseResult(resultCode, intent)
    }
}
