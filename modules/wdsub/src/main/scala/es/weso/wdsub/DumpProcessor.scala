package es.weso.wdsub
import org.wikidata.wdtk.dumpfiles._
import cats.effect._
import cats.implicits._
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path
import es.weso.shex
import java.io.OutputStream
import org.apache.commons.compress.compressors.gzip._
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.io.BufferedOutputStream

object DumpProcessor {

    private lazy val dumpProcessingController: DumpProcessingController = new DumpProcessingController("wdsubDump")
    private lazy val logger = LoggerFactory.getLogger(this.getClass().getCanonicalName());

    private def info(msg: String): IO[Unit] = IO {
        logger.info(msg)
    }

    private def acquireShEx(schemaPath: Path, verbose: Boolean): IO[Schema] = for {
      schema <- shex.Schema.fromFile(schemaPath.toFile().getAbsolutePath())
      resolvedSchema <- shex.ResolvedSchema.resolve(schema, None)
      wshex <- IO.fromEither(ShEx2WShEx.convertSchema(resolvedSchema))
      _ <- info(s"ShEx Schema: $wshex")
    } yield wshex 
    
    private def acquireShExProcessor(schemaPath: Path, outputPath: Path, verbose: Boolean, timeout: Int): IO[WShExProcessor] = for {
      wshex <- acquireShEx(schemaPath, verbose) 
      out <- acquireOutput(outputPath)
    } yield new WShExProcessor(wshex, out, verbose, timeout)

    private def mkShExProcessor(schema: Path, outputPath: Path, verbose: Boolean, timeout: Int): Resource[IO, WShExProcessor] = 
       Resource.make(acquireShExProcessor(schema,outputPath,verbose,timeout))(e => IO { e.close() })

    private def acquireMwLocalDumpFile(file: Path, verbose: Boolean): IO[MwLocalDumpFile] = {
      val fileName = file.toFile().getAbsolutePath()
      info(s"File: $fileName") *> IO { new MwLocalDumpFile(fileName) }
    } 

    private def mkDumpFile(file: Path, verbose: Boolean): Resource[IO, MwLocalDumpFile] = 
       Resource.make(acquireMwLocalDumpFile(file, verbose))(
         mwFile => IO { mwFile.getDumpFileStream().close() }
       )

    // TODO: Check if we can run this in a different thread?   
    // Some snippets have been taken from: 
    // https://github.com/bennofs/wdumper/blob/master/src/main/java/io/github/bennofs/wdumper/DumpRunner.java#L97
    private def acquireOutput(outputPath: Path): IO[OutputStream] = IO {
      val fileStream: OutputStream = Files.newOutputStream(outputPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
      val bufferedStream: OutputStream = new BufferedOutputStream(fileStream, 10 * 1024 * 1024)
      val gzipParams = new GzipParameters()
      gzipParams.setCompressionLevel(1) 
      val outputStream: OutputStream = new GzipCompressorOutputStream(bufferedStream, gzipParams)
      outputStream
    }

    private def releaseOutput(out: OutputStream): IO[Unit] = IO { out.close() }
    private def mkOutput(outputPath: Path): Resource[IO, OutputStream] = 
     Resource.make(acquireOutput(outputPath))(releaseOutput)
    
    /**
      * Dump process
      *
      * @param fileName name of filename to process
      * @param schema ShEx schema
      * @param verbose if true, show info messages
      * @param timeout timeout in seconds or 0 if no timeout should be used
      * @return dump results
      */   
    def dumpProcess(filePath: Path, outPath: Path, schema: Path, verbose: Boolean, timeout: Int): IO[DumpResults] = {
       for {
        results <- (mkShExProcessor(schema,outPath,verbose,timeout), mkDumpFile(filePath, verbose)).tupled.use {
            case (processor, mwDumpFile) => for {
              _ <- IO { LogConfig.configureLogging() }  
              _ <- IO { dumpProcessingController.registerEntityDocumentProcessor(processor, null, true) }
              _ <- info(s"Processing local dump: $filePath")
              _ <- info(s"DateStamp: ${mwDumpFile.getDateStamp()}")
              _ <- info(s"Available?: ${mwDumpFile.isAvailable()}")
              _ <- IO { dumpProcessingController.processDump(mwDumpFile) }
              totalEntities <- processor.getTotalEntities()
              matchedEntities <- processor.getMatchedEntities()
            } yield DumpResults(totalEntities, matchedEntities)
        }
       } yield results
    }
}


