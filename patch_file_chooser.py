with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

# Update fileChooserLauncher to handle MULTIPLE file selection natively
file_chooser_new = """    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (filePathCallback == null) return@registerForActivityResult
        var results: Array<Uri>? = null
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            if (data?.clipData != null) {
                // Handle multiple files
                val count = data.clipData!!.itemCount
                results = Array(count) { i ->
                    data.clipData!!.getItemAt(i).uri
                }
            } else if (data?.data != null) {
                // Handle single file
                results = arrayOf(data.data!!)
            }
        }
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }"""

content = content.replace("""    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (filePathCallback == null) return@registerForActivityResult
        var results: Array<Uri>? = null
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.dataString?.let {
                results = arrayOf(Uri.parse(it))
            }
        }
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }""", file_chooser_new)


on_show_file_chooser_new = """                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = filePathCallback
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        val acceptedTypes = fileChooserParams?.acceptTypes
                        if (acceptedTypes != null && acceptedTypes.isNotEmpty()) {
                            putExtra(Intent.EXTRA_MIME_TYPES, acceptedTypes)
                        }
                        if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                    }
                    try {
                        fileChooserLauncher.launch(intent)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(this@MainActivity, "Cannot open file chooser", Toast.LENGTH_LONG).show()
                        return false
                    }
                    return true
                }"""

content = content.replace("""                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = filePathCallback
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    try {
                        fileChooserLauncher.launch(intent)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(this@MainActivity, "Cannot open file chooser", Toast.LENGTH_LONG).show()
                        return false
                    }
                    return true
                }""", on_show_file_chooser_new)

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)
