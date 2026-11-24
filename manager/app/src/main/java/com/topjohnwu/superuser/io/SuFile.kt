package com.topjohnwu.superuser.io

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

class SuFile : File {
    constructor(path: String) : super(path)
    constructor(parent: File, child: String) : super(parent, child)
    fun newInputStream(): InputStream = FileInputStream(this)
    fun newOutputStream(): OutputStream = FileOutputStream(this)
}

object SuFileInputStream {
    @JvmStatic fun open(file: File): InputStream = FileInputStream(file)
}

object SuFileOutputStream {
    @JvmStatic fun open(file: File): OutputStream = FileOutputStream(file)
}
