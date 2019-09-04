package kr.djgis.shpbackup3

import java.io.File
import kr.djgis.shpbackup3.network.PostgresConnectionPool
import kr.djgis.shpbackup3.property.Config
import kr.djgis.shpbackup3.property.Status
import kr.djgis.shpbackup3.property.at
import org.opengis.feature.simple.SimpleFeature
import org.postgresql.util.PSQLException

class ExecuteShp(private val file: File) {

    private var errorCount = 0
    private val fileName = file.nameWithoutExtension
    private val tableCode = fileName at tableList

    @Throws(Throwable::class)
    fun run() {
        val pConn = PostgresConnectionPool.getConnection()
        pConn.open(true) { pConnection ->
            pConnection.createStatement().use { pStmt ->
                val featureCollection = getFeatureCollection(file)
                val features = arrayOfNulls<SimpleFeature>(featureCollection.size())
                featureCollection.toArray(features)
                val metaData = featureCollection.schema.attributeDescriptors
                val columnCount = metaData.size
                val columnNames = arrayOfNulls<String>(columnCount)
                columnNames[0] = "\"geom\""
                for (i in 1 until columnCount) {
                    columnNames[i] = "\"${metaData[i].localName}\""
                }
                val columnList = columnNames.joinToString(",").toLowerCase().trim()
                if (Status.tableCodeSet.add(tableCode)) {
                    pStmt.execute("TRUNCATE TABLE $tableCode")
                    pStmt.execute("SELECT SETVAL('public.${tableCode}_id_seq',1,false)")
                }
                features.forEach feature@{ feature ->
                    val columnValues = arrayOfNulls<String>(columnCount + 1)
                    val coordinate = setupCoordinate(feature!!)
                    columnValues[0] = "st_geomfromtext('$coordinate', ${Config.origin})"
                    for (j in 1 until columnCount) {
                        val field = ValueField(null, feature.getAttribute(j).toString())
                        columnValues[j] = field.value
                    }
                    val valueList = columnValues.joinToString(",").trim()
                    val insertQuery = "INSERT INTO $tableCode ($columnList) VALUES ($valueList)"
                    try {
                        pStmt.execute(insertQuery)
                    } catch (e: PSQLException) {
                        errorCount += 1
                        logger.error("$fileName $tableCode ${e.message}")
                    } finally {
                        pConnection.commit()
                    }
                }
                pConnection.reportResults(
                    fileName = fileName,
                    tableCode = tableCode,
                    rowCount = features.size,
                    errorCount = errorCount
                )
            }
        }
    }
}