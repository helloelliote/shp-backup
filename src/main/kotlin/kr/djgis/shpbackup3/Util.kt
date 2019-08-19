package kr.djgis.shpbackup3

import kr.djgis.shpbackup3.network.PostgresConnectionPool
import kr.djgis.shpbackup3.property.Config
import org.opengis.feature.simple.SimpleFeature
import java.io.BufferedReader
import java.io.FileReader
import java.sql.Connection
import java.sql.Types
import java.text.ParseException
import java.text.SimpleDateFormat

const val ANSI_RESET = "\u001B[0m"
const val ANSI_RED = "\u001B[31m"
const val ANSI_GREEN = "\u001B[32m"
const val ANSI_YELLOW = "\u001B[33m"
const val ANSI_BLUE = "\u001B[34m"
const val ANSI_PURPLE = "\u001B[35m"
const val ANSI_CYAN = "\u001B[36m"

@Throws(Throwable::class)
inline fun <R> Connection.open(rollback: Boolean = false, block: (Connection) -> R): R {
    try {
        if (rollback) {
            this.autoCommit = false
            this.setSavepoint()
        }
        return block(this)
    } catch (e: Throwable) {
        e.printStackTrace()
        throw e
    } finally {
        close()
    }
}

fun setupFtrIdn(feature: SimpleFeature): String {
    return when (feature.getProperty("관리번호")) {
        null -> feature.getProperty("FTR_IDN").value.toString()
        else -> feature.getProperty("관리번호").value.toString()
    }
}

fun setupCoordinate(feature: SimpleFeature): String {
    return when ("MULTI" in "${feature.getAttribute(0)}") {
        false -> "MULTI${feature.getAttribute(0)}"
        true -> "${feature.getAttribute(0)}"
    }
}

fun executePostQuery() {
    val pConn1 = PostgresConnectionPool.getConnection()
    pConn1.open {
        try {
            it.createStatement().execute("VACUUM verbose analyze")
        } catch (e: Exception) {
            logger.error(e.message)
        }
    }.also {
        val pConn2 = PostgresConnectionPool.getConnection()
        pConn2.open(true) { postgres ->
            postgres.createStatement().use { pStmt ->
                BufferedReader(FileReader("./postquery.txt")).use {
                    try {
                        var line: String? = it.readLine()
                        while (line != null) {
                            println(line)
                            line = it.readLine()
                            pStmt.execute(line)
                        }
                    } catch (e: Exception) {
                        logger.error(e.message)
                        postgres.rollback()
                        println("${Config.local} 업데이트 롤백")
                        return@executePostQuery
                    } finally {
                        postgres.commit()
                    }
                }
                println("${Config.local} 업데이트 완료")
            }
        }
    }
}

class ValueField(private val columnType: Int, private val columnValue: String?) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd")

    /**
     * @return columnType 을 받아 SQL query syntax 에 맞게 가공
     */
    val value: String
        @Throws(ParseException::class)
        get() = when {
            columnValue != null -> {
                val dataFormat: String
                var dataValue: String = columnValue
                when (columnType) {
                    Types.TIMESTAMP, Types.DATE -> {
                        dataFormat = "'%s'"
                        dataValue = dateFormat.format(dateFormat.parse(columnValue))
                    }
                    Types.INTEGER, Types.DECIMAL, Types.DOUBLE -> {
                        dataFormat = "%s"
                    }
                    Types.CHAR, Types.VARCHAR, Types.TIME -> {
                        dataFormat = "'%s'"
                    }
                    else -> dataFormat = "'%s'"
                }
                String.format(dataFormat, dataValue)
            }
            else -> {
                "null"
            }
        }
}