package dev.fughetta.dshcompose.protocol

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OfficePdfApiTest {
    @Test
    fun convertedPdfStreamsToTheDestinationAndPreservesFontsLimitsAndRemoteErrors() = runBlocking {
        val pdf = "%PDF-1.4\n".toByteArray() + ByteArray(200000) { (it % 251).toByte() }
        var conversions = 0
        val engine = MockEngine { request ->
            conversions++
            val input = parseObject((request.body as TextContent).text)
            assertEquals("officeToPdf/render", input.string("method"))
            val args = input.getValue("payload").objectValue().getValue("args").objectValue()
            assertEquals("s1", args.string("workspaceFileScopeId"))
            assertEquals("foreground", args.string("priority"))
            val denied = args.string("path") == "denied.docx"
            val metadata = buildJsonObject {
                put("type", "server-response")
                put("rpcId", input.getValue("rpcId"))
                putJsonObject("result") {
                    put("ok", !denied)
                    if (denied) putJsonObject("error") { put("code", "workspace-file/unsupported") }
                    else
                        putJsonObject("value") {
                            put("data", JsonNull)
                            putJsonArray("missingFonts") { add("Calibri") }
                        }
                }
                if (!denied)
                    putJsonArray("attachments") {
                        addJsonObject {
                            put("codec", "bytes")
                            put("part", "pdf")
                            putJsonArray("path") { add("data") }
                        }
                    }
            }
            if (denied)
                respond(
                    metadata.toString(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            else {
                val body =
                    ByteArrayOutputStream()
                        .apply {
                            write(
                                "--office\r\nContent-Disposition: form-data; name=\"metadata\"\r\n\r\n$metadata\r\n--office\r\nContent-Disposition: form-data; name=\"pdf\"; filename=\"converted.pdf\"\r\nContent-Type: application/pdf\r\n\r\n"
                                    .toByteArray(),
                            )
                            write(pdf)
                            write("\r\n--office--\r\n".toByteArray())
                        }
                        .toByteArray()
                respond(
                    body,
                    headers =
                        headersOf(HttpHeaders.ContentType, "multipart/form-data; boundary=office"),
                )
            }
        }
        ProtocolClient(HostConnection("http://localhost:3080", engine = engine)).use { client ->
            val output = ByteArrayOutputStream()
            val result = client.renderOfficePdf("s1", "report.docx", output)
            assertEquals(pdf.size.toLong(), result.bytes)
            assertArrayEquals(pdf, output.toByteArray())
            assertEquals(listOf("Calibri"), result.missingFonts)
            val limited = ByteArrayOutputStream()
            try {
                client.renderOfficePdf("s1", "report.docx", limited, maximumBytes = 70000)
                fail()
            } catch (error: HostRemoteException) {
                assertEquals("workspace-file/too-large", error.code)
            }
            assertTrue(limited.size() <= 70000)
            try {
                client.renderOfficePdf("s1", "denied.docx", ByteArrayOutputStream())
                fail()
            } catch (error: HostRemoteException) {
                assertEquals("workspace-file/unsupported", error.code)
            }
            assertEquals(3, conversions)
        }
    }
}
