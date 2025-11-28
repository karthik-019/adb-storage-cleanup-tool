package com.example.onepluscleaner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.DecimalFormat
import kotlin.coroutines.CoroutineContext

class MainActivity : AppCompatActivity(), CoroutineScope {
    private val TAG = "OnePlusCleaner"
    private val PREFS = "oneplus_cleaner_prefs"
    private val TREE_KEY = "tree_uris" // CSV stored

    private lateinit var btnAddFolder: Button
    private lateinit var btnScan: Button
    private lateinit var btnDelete: Button
    private lateinit var listView: ListView

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext get() = Dispatchers.Main + job

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            Toast.makeText(this, "Folder pick canceled", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        addPersistedTree(uri)
        Toast.makeText(this, "Folder saved: ${uri.path}", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnAddFolder = findViewById(R.id.btnPick)
        btnScan = findViewById(R.id.btnScan)
        btnDelete = findViewById(R.id.btnDelete)
        listView = findViewById(R.id.listView)

        btnAddFolder.setOnClickListener { pickFolderFlow() }
        btnScan.setOnClickListener { scanAllPickedTrees() }
        btnDelete.setOnClickListener { confirmAndDeleteSelected() }

        val source = intent?.getStringExtra("source")
        if (source == "adb_companion") {
            if (getPersistedTrees().isEmpty()) {
                Toast.makeText(this, "Please pick folders to allow the cleanup.", Toast.LENGTH_LONG).show()
                launchPick()
            } else {
                Toast.makeText(this, "Companion launched. Use Scan to preview cleanup.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun pickFolderFlow() { launchPick() }
    private fun launchPick() { pickLauncher.launch(null) }

    private fun addPersistedTree(uri: Uri) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val existing = prefs.getString(TREE_KEY, "") ?: ""
        val updated = if (existing.isEmpty()) uri.toString() else "$existing|${uri}"
        prefs.edit().putString(TREE_KEY, updated).apply()
    }

    private fun getPersistedTrees(): List<Uri> {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val csv = prefs.getString(TREE_KEY, "") ?: ""
        if (csv.isEmpty()) return emptyList()
        return csv.split('|').mapNotNull { s -> try { Uri.parse(s) } catch (e:Exception){ null } }
    }

    private fun scanAllPickedTrees() {
        val trees = getPersistedTrees()
        if (trees.isEmpty()) {
            Toast.makeText(this, "No folders picked. Tap 'Pick folder' first.", Toast.LENGTH_SHORT).show()
            return
        }
        launch {
            val allItems = mutableListOf<Pair<DocumentFile, Long>>()
            withContext(Dispatchers.IO) {
                trees.forEach { t ->
                    val doc = DocumentFile.fromTreeUri(this@MainActivity, t)
                    doc?.listFiles()?.forEach { child ->
                        val size = computeSizeLimited(child)
                        allItems.add(Pair(child, size))
                    }
                }
            }
            allItems.sortByDescending { it.second }
            val items = allItems.map { "${it.first.name} — ${readableFileSize(it.second)}" }
            listView.adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_multiple_choice, items)
            listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
            Toast.makeText(this@MainActivity, "Scan complete: ${allItems.size} items", Toast.LENGTH_SHORT).show()
            listView.tag = allItems
        }
    }

    private suspend fun computeSizeLimited(file: DocumentFile): Long = withContext(Dispatchers.IO) {
        if (file.isFile) return@withContext try { file.length() } catch (e: Exception) { 0L }
        var total = 0L
        val children = file.listFiles()
        children.forEach { c -> total += computeSizeLimited(c) }
        total
    }

    private fun readableFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B","KB","MB","GB","TB")
        val digitGroups = (Math.log10(size.toDouble())/Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(size/Math.pow(1024.0, digitGroups.toDouble()))+" "+units[digitGroups]
    }

    private fun confirmAndDeleteSelected() {
        val items = listView.tag as? List<*> ?: emptyList<Any>()
        if (items.isEmpty()) {
            Toast.makeText(this, "Nothing to delete. Scan first.", Toast.LENGTH_SHORT).show()
            return
        }
        val checked = listView.checkedItemPositions
        val toDelete = mutableListOf<DocumentFile>()
        items.forEachIndexed { index, any ->
            if (checked.get(index)) {
                val pair = any as Pair<*, *>
                val doc = pair.first as DocumentFile
                toDelete.add(doc)
            }
        }
        if (toDelete.isEmpty()) {
            Toast.makeText(this, "No items selected.", Toast.LENGTH_SHORT).show()
            return
        }
        var total = 0L
        toDelete.forEach { total += try { computeSizeLimitedSync(it) } catch (_:Exception){0L} }
        val message = "You are about to delete ${toDelete.size} items totaling ${readableFileSize(total)}.\n\nTop items:\n" +
                toDelete.take(6).joinToString("\n") { it.name ?: "unknown" } + "\n\nProceed?"

        AlertDialog.Builder(this)
            .setTitle("Confirm deletion")
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ -> runDeletionAsync(toDelete) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun computeSizeLimitedSync(f: DocumentFile): Long {
        if (f.isFile) return try { f.length() } catch (_: Exception) { 0L }
        var total = 0L
        f.listFiles().forEach { c -> total += computeSizeLimitedSync(c) }
        return total
    }

    private fun runDeletionAsync(list: List<DocumentFile>) {
        launch {
            var deleted = 0
            var failed = 0
            withContext(Dispatchers.IO) {
                list.forEach { item ->
                    val ok = try {
                        val uri = item.uri
                        val cr = contentResolver
                        var success = false
                        try { success = DocumentsContract.deleteDocument(cr, uri) } catch (e: Exception) { success = false }
                        if (!success) success = deleteRecursivelyFallback(item)
                        success
                    } catch (e: Exception) { false }
                    if (ok) deleted++ else failed++
                }
            }
            Toast.makeText(this@MainActivity, "Deleted $deleted, failed $failed", Toast.LENGTH_LONG).show()
            writeLog("Deleted:$deleted Failed:$failed")
        }
    }

    private fun deleteRecursivelyFallback(root: DocumentFile): Boolean {
        try {
            if (root.isFile) return root.delete()
            val children = root.listFiles()
            children.forEach { deleteRecursivelyFallback(it) }
            return root.delete()
        } catch (e: Exception) {
            Log.w(TAG, "delete fail", e)
            return false
        }
    }

    private fun writeLog(text: String) {
        try {
            val file = File(filesDir, "cleanup_log.txt")
            FileOutputStream(file, true).use { out -> out.write((System.currentTimeMillis().toString() + " - " + text + "\n").toByteArray()) }
            try {
                val pub = File(getExternalFilesDir(null)?.parentFile?.parentFile?.absolutePath + "/Download", "oneplus_cleanup_report.txt")
                FileOutputStream(pub, true).use { out -> out.write((text+"\n").toByteArray()) }
            } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.w(TAG, "log write failed", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineContext.cancel()
    }
}
