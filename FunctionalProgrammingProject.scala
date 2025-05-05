import cats.effect.*
import cats.effect.std.Console
import cats.effect.kernel.Ref
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.blaze.server.*
import org.http4s.implicits.*
import org.http4s.circe.*
import io.circe.generic.auto.*
import io.circe.syntax.*
import requests.*
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import scala.concurrent.duration.*
import geny.Generator.from
import java.time.LocalDate
import scala.collection.mutable

object API_requests extends IOApp {

  private val apiKey = "c30de98f71084a629ef43ccc606b4118"

  case class DataRow(startTime: String, endTime: String, value: String)

  def init: IO[(Ref[IO, List[Float]], Ref[IO, List[Float]], Ref[IO, List[Float]], Ref[IO, List[Float]], Ref[IO, List[Float]])] =
    for {
      wind      <- Ref.of[IO, List[Float]](Nil)
      solar     <- Ref.of[IO, List[Float]](Nil)
      hydro     <- Ref.of[IO, List[Float]](Nil)
      prod      <- Ref.of[IO, List[Float]](Nil)
      cons      <- Ref.of[IO, List[Float]](Nil)
    } yield (wind, solar, hydro, prod, cons)

  def formatDateTime(isoString: String): String = {
    val input = ZonedDateTime.parse(isoString)
    val outputFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    input.format(outputFormat)
  }

  def parseAndSum(path: String)(filter: (LocalDate, Float) => Boolean): Float = {
    val lines = Files.readAllLines(Path.of(path), StandardCharsets.UTF_8).toArray().toList.map(_.toString)
    val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    lines.flatMap { line =>
      val parts = line.split(",")
      if (parts.length == 3) {
        val time = parts(0)
        val parsedTime = scala.util.Try(java.time.LocalDateTime.parse(time, formatter)).toOption
        val value = scala.util.Try(parts(2).toFloat).toOption
        for {
          t <- parsedTime if filter(t.toLocalDate, value.getOrElse(0f))
          v <- value
        } yield v
      } else None
    }.sum
  }

  def calculateSummary: IO[(Float, Float)] = IO.blocking {
    val now = java.time.LocalDate.now()
    val energyFilter: (LocalDate, Float) => Boolean = (date, _) => !date.isAfter(now)

    val production = List("wind.csv", "solar.csv", "hydro.csv", "production.csv").map(path => parseAndSum(path)(energyFilter)).sum
    val consumption = parseAndSum("consumption.csv")(energyFilter)
    (production, consumption)
  }

  def createDailyHistoryCSV(): IO[Unit] = IO.blocking {
    val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    val paths = Map("wind" -> "wind.csv", "solar" -> "solar.csv", "hydro" -> "hydro.csv")
    val dailySums = mutable.Map.empty[String, mutable.Map[String, Float]]

    for ((kind, path) <- paths) {
      val lines = Files.readAllLines(Path.of(path), StandardCharsets.UTF_8).toArray().toList.map(_.toString)
      lines.foreach { line =>
        val parts = line.split(",")
        if (parts.length == 3) {
          val date = scala.util.Try {
            java.time.LocalDateTime.parse(parts(0), formatter).toLocalDate.toString
          }.getOrElse("")
          val value = scala.util.Try(parts(2).toFloat).getOrElse(0f)
          val kindMap = dailySums.getOrElseUpdate(date, mutable.Map("wind" -> 0f, "solar" -> 0f, "hydro" -> 0f))
          kindMap(kind) += value
        }
      }
    }

    val csvLines = dailySums.toList.sortBy(_._1).map { case (date, m) =>
      s"$date,${m("wind")},${m("solar")},${m("hydro")}"
    }

    val header = "DATE,WindSum,SolarSum,HydroSum"
    Files.write(Path.of("history.csv"), (header + "\n" + csvLines.mkString("\n")).getBytes(StandardCharsets.UTF_8))
  }

  def fetchData(datasetId: Int, storage: Ref[IO, List[Float]], filename: String): IO[List[DataRow]] = IO {
    val res = requests.get(
      s"https://data.fingrid.fi/api/datasets/$datasetId/data?startTime=2025-03-01T12:00:00.000Z&pageSize=20000&locale=en",
      headers = Seq("x-api-key" -> apiKey)
    )
    val json = ujson.read(res.text())
    val rows = json("data").arr

    rows.toList.map { row =>
      val obj = row.obj
      DataRow(
        formatDateTime(obj("startTime").str),
        formatDateTime(obj("endTime").str),
        obj("value").toString()
      )
    }
  }.flatTap { data =>
    val floats = data.flatMap(row => scala.util.Try(row.value.toFloat).toOption)
    storage.set(floats)
  }.flatTap { data =>
    IO.blocking {
      val content = data.map(row => s"${row.startTime},${row.endTime},${row.value}").mkString("\n")
      val path = java.nio.file.Path.of(filename)
      java.nio.file.Files.write(path, content.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }

  override def run(args: List[String]): IO[ExitCode] =
    for {
      refs <- init
      (windRef, solarRef, hydroRef, prodRef, consRef) = refs
      _ <- createDailyHistoryCSV()
      _ <- fetchData(75, windRef, "wind.csv")
      _ <- IO.sleep(2.seconds)
      _ <- fetchData(248, solarRef, "solar.csv")
      _ <- IO.sleep(2.seconds)
      _ <- fetchData(191, hydroRef, "hydro.csv")
      _ <- IO.sleep(2.seconds)
      _ <- fetchData(74, prodRef, "production.csv")
      _ <- IO.sleep(2.seconds)
      _ <- fetchData(124, consRef, "consumption.csv")

      routes = HttpRoutes.of[IO] {
        case GET -> Root =>
          Ok(htmlPage, Header.Raw(org.typelevel.ci.CIString("Content-Type"), "text/html"))

        case GET -> Root / "api" / "wind" =>
          fetchData(75, windRef, "wind.csv").map(_.asJson).flatMap(Ok(_))

        case GET -> Root / "api" / "solar" =>
          fetchData(248, solarRef, "solar.csv").map(_.asJson).flatMap(Ok(_))

        case GET -> Root / "api" / "hydro" =>
          fetchData(191, hydroRef, "hydro.csv").map(_.asJson).flatMap(Ok(_))

        case GET -> Root / "api" / "production" =>
          fetchData(74, prodRef, "production.csv").map(_.asJson).flatMap(Ok(_))

        case GET -> Root / "api" / "consumption" =>
          fetchData(124, consRef, "consumption.csv").map(_.asJson).flatMap(Ok(_))

        case GET -> Root / "api" / "summary" =>
          calculateSummary.flatMap { case (prod, cons) =>
            val json = Map(
              "totalProduction" -> prod,
              "totalConsumption" -> cons
            ).asJson
            Ok(json)
          }

        case GET -> Root / "api" / "history" =>
          IO.blocking {
            val lines = Files.readAllLines(Path.of("history.csv"), StandardCharsets.UTF_8).toArray().toList.map(_.toString).drop(1)
            lines.map { line =>
              val Array(date, wind, solar, hydro) = line.split(",")
              Map("date" -> date, "wind" -> wind, "solar" -> solar, "hydro" -> hydro)
            }
          }.map(_.asJson).flatMap(Ok(_))

        case GET -> Root / "api" / "stats" / dataType =>
          val refOpt = dataType match {
            case "wind"        => Some(windRef)
            case "solar"       => Some(solarRef)
            case "hydro"       => Some(hydroRef)
            case "production"  => Some(prodRef)
            case "consumption" => Some(consRef)
            case _             => None
          }

          refOpt match {
            case Some(ref) =>
              for {
                list <- ref.get
                res  <- {
                  val stats = Map(
                    "mean"     -> mean(list),
                    "median"   -> median(list),
                    "mode"     -> mode(list),
                    "range"    -> range(list),
                    "midrange" -> midrange(list)
                  )
                  Ok(stats.asJson)
                }
              } yield res

            case None => NotFound("Unknown dataset type.")
          }
        case req@GET -> Root / "api" / "file" / dataType =>
          val filename = s"$dataType.csv"
          val startOpt = req.params.get("start")
          val endOpt = req.params.get("end")

          val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

          def parseDate(d: String) =
            scala.util.Try(java.time.LocalDate.parse(d, dateFormatter)).toOption

          val today = java.time.LocalDate.now()
          val startDate = startOpt.flatMap(parseDate).getOrElse(today)
          val endDate = endOpt.flatMap(parseDate).getOrElse(today)

          val filtered = IO.blocking {
            val lines = Files.readAllLines(Path.of(filename), StandardCharsets.UTF_8).toArray().toList.map(_.toString)
            lines.flatMap { line =>
              val parts = line.split(",")
              if (parts.length == 3) {
                val startTime = parts(0)
                val endTime = parts(1)
                val value = parts(2)

                val lineDate = scala.util.Try {
                  java.time.LocalDate.parse(startTime.substring(0, 10), DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                }.toOption

                lineDate.filter(!_.isBefore(startDate)).filter(!_.isAfter(endDate)).map { _ =>
                  DataRow(startTime, endTime, value)
                }
              } else None
            }
          }

          filtered.flatMap { data =>
            val values = data.flatMap(row => scala.util.Try(row.value.toFloat).toOption)
            val stats = Map(
              "mean"     -> mean(values),
              "median"   -> median(values),
              "mode"     -> mode(values),
              "range"    -> range(values),
              "midrange" -> midrange(values)
            )
            val responseJson = Map(
              "data" -> data.asJson,
              "stats" -> stats.asJson
            ).asJson
            Ok(responseJson)
          }
      }

      _ <- BlazeServerBuilder[IO]
        .bindHttp(8080, "localhost")
        .withHttpApp(routes.orNotFound)
        .serve
        .compile
        .drain

    } yield ExitCode.Success

  val htmlPage: String =
    """
      |<!DOCTYPE html>
      |<html lang="en">
      |<head>
      |  <meta charset="UTF-8" />
      |  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
      |  <title>REPS</title>
      |  <style>
      |  body { font-family: Arial; margin: 20px; }
      |  .tabs button { padding: 10px 20px; margin-right: 5px; }
      |  .tabs button.selected { background-color: #007bff; color: white; }
      |  table { width: 100%; border-collapse: collapse; margin-top: 20px; }
      |  th, td { border: 1px solid #ccc; padding: 8px; }
      |  th { background: #f4f4f4; }
      |  .stats { margin-top: 20px; }
      |  .stats label { display: block; margin: 5px 0; }
      |  .low    { background-color: #ffdddd; }
      |  .medium { background-color: #ffffcc; }
      |  .high   { background-color: #ddffdd; }
      |  </style>
      |</head>
      |<body>
      |  <h2>Renewable Energy Plant Data</h2>
      |  <div class="tabs">
      |    <button onclick="loadData('wind')">Wind</button>
      |    <button onclick="loadData('solar')">Solar</button>
      |    <button onclick="loadData('hydro')">Hydropower</button>
      |    <button onclick="loadData('production')">Electricity Production</button>
      |    <button onclick="loadData('consumption')">Electricity Consumption</button>
      |    <input type="text" id="startDate" placeholder="Start (DD/MM/YYYY)" />
      |    <input type="text" id="endDate" placeholder="End (DD/MM/YYYY)" />
      |    <button onclick="loadDataWithDate()">Load</button>
      |    <button onclick="loadHistory()">Load History</button>
      |  </div>
      |  <div style="display: flex; gap: 40px;">
      |  <div>
      |    <h3>Production Summary</h3>
      |    <label>Total Production: <span id="totalProd">0</span> MW</label><br>
      |    <label>Total Consumption: <span id="totalCons">0</span> MW</label><br>
      |    <label>Difference: <span id="difference">0</span> MW</label><br>
      |    <label>Storage Capacity:</label>
      |    <progress id="storageBar" value="0" max="100000000" style="width: 200px;"></progress>
      |  </div>
      |  <div id="dataTable">
      |    <p>Select a tab to load data.</p>
      |  </div>
      |  <div id="historyTable">
      |   <p>Click 'Load History' to view historical daily data.</p>
      |   </div>
      |
      |<script>
      |let currentType = 'wind';
      |let sortAscending = true;
      |let currentData = [];
      |let currentStats = {};
      |
      |function loadData(type) {
      |  currentType = type;
      |  document.querySelectorAll(".tabs button").forEach(btn => {
      |    btn.classList.remove("selected");
      |    if (btn.textContent.toLowerCase().includes(type)) {
      |      btn.classList.add("selected");
      |    }
      |  });
      |  loadDataWithDate();
      |}
      |
      |function loadDataWithDate() {
      |  const start = document.getElementById('startDate').value;
      |  const end = document.getElementById('endDate').value;
      |
      |  const url = new URL('/api/file/' + currentType, window.location.origin);
      |  if (start) url.searchParams.append('start', start);
      |  if (end) url.searchParams.append('end', end);
      |
      |  fetch(url)
      |  .then(res => res.json())
      |  .then(result => {
      |    currentData = result.data;
      |    currentStats = result.stats;
      |    renderTable();
      |
      |    const stats = result.stats;
      |    document.getElementById('mean').textContent = stats.mean;
      |    document.getElementById('median').textContent = stats.median;
      |    document.getElementById('mode').textContent = stats.mode;
      |    document.getElementById('range').textContent = stats.range;
      |    document.getElementById('midrange').textContent = stats.midrange;
      |  });
      |}
      |
      |function loadSummary() {
      |  fetch("/api/summary")
      |    .then(res => res.json())
      |    .then(data => {
      |      document.getElementById("totalProd").textContent = data.totalProduction.toFixed(2);
      |      document.getElementById("totalCons").textContent = data.totalConsumption.toFixed(2);
      |      document.getElementById("difference").textContent = (data.totalProduction - data.totalConsumption).toFixed(2);
      |
      |      const percent = Math.min(100000000, Math.max(0, (data.totalProduction - data.totalConsumption)));
      |      document.getElementById("storageBar").value = percent;
      |    });
      |}
      |window.onload = function () {
      |  loadData('wind');
      |  loadSummary();
      |};
      |
      |function renderTable() {
      |  const unit = ["solar", "production", "consumption"].includes(currentType) ? "MWh/h" : "MW";
      |  const thresholds = {
      |    wind: [4400, 5000],
      |    solar: [80, 150],
      |    hydro: [1290, 1310],
      |    production: [9900, 10500],
      |    consumption: [9850, 9750]
      |  };
      |  const [low, high] = thresholds[currentType] || [30, 70];
      |
      |  const rowsHtml = currentData.map(row => {
      |    const value = parseFloat(row.value);
      |    let cls = '';
      |    if (!isNaN(value)) {
      |      if (currentType === "consumption") {
      |        if (value < high) cls = 'high';
      |        else if (value > low) cls = 'low';
      |        else cls = 'medium';
      |      } else {
      |        if (value < low) cls = 'low';
      |        else if (value < high) cls = 'medium';
      |        else cls = 'high';
      |      }
      |    }
      |    return `<tr><td>${row.startTime}</td><td>${row.endTime}</td><td class="${cls}">${row.value} ${unit}</td></tr>`;
      |  }).join('');
      |
      |  const html = `
      |  <h3>Selected Data and Statistics</h3>
      |  <div class="stats">
      |     <label>Mean: ${currentStats.mean ?? ''}</label>
      |     <label>Median: ${currentStats.median ?? ''}</label>
      |     <label>Mode: ${currentStats.mode ?? ''}</label>
      |     <label>Range: ${currentStats.range ?? ''}</label>
      |     <label>Midrange: ${currentStats.midrange ?? ''}</label>
      |  </div>
      |  <table>
      |    <tr>
      |      <th>Start Time</th>
      |      <th>End Time</th>
      |      <th onclick="toggleSort()" style="cursor:pointer;">
      |        Value <span id="sortIcon">${sortAscending ? '▲' : '▼'}</span>
      |      </th>
      |    </tr>
      |    ${rowsHtml}
      |  </table>
      |`;
      |  document.getElementById('dataTable').innerHTML = html;
      |}
      |
      |function toggleSort() {
      |  sortAscending = !sortAscending;
      |  currentData.sort((a, b) => {
      |    const va = parseFloat(a.value);
      |    const vb = parseFloat(b.value);
      |    return sortAscending ? va - vb : vb - va;
      |  });
      |  renderTable();
      |}
      |function loadHistory() {
      |  fetch('/api/history')
      |    .then(res => res.json())
      |    .then(data => {
      |      const rows = data.map(row =>
      |        `<tr><td>${row.date}</td><td>${row.wind}</td><td>${row.solar}</td><td>${row.hydro}</td></tr>`
      |      ).join('');
      |      const table = `
      |        <table>
      |          <tr><th>Date</th><th>Wind</th><th>Solar</th><th>Hydropower</th></tr>
      |          ${rows}
      |        </table>
      |      `;
      |      document.getElementById("historyTable").innerHTML = table;
      |    });
      |}
      |</script>
      |</body>
      |</html>
      |""".stripMargin

  def sumList(list: List[Float]): Float = {
    val w = list.length
    @annotation.tailrec
    def go(l: List[Float], acc: Float, n: Int): Float =
      if (n == w) acc // Return accumulator when the end of the list is reached
      else go(l, acc + l(n), n + 1) //Call the next step in recursion
    go(list, 0, 0) // First call of the go function to initialise the recursion
  }

  def mean(list: List[Float]): Float = {
    val sum = sumList(list)
    val n = list.length
    sum / n
  }

  def median(list: List[Float]): Float = {
    val n = list.length
    val list2 = list.sorted
    if (n%2==0) {
      list2(n/2)+list2(n/2-1)
    } else {
      list2(math.ceil(n/2).toInt)
    }
  }

  def mode(list: List[Float]): Float = {
    val grouped = list.groupBy(identity).view.mapValues(_.size)
    val maxCount = grouped.values.max
    val modes = grouped.filter(_._2 == maxCount).keys
    modes.min
  }

  def range(list: List[Float]): Float = {
    val list2 = list.sorted
    list2.last- list2.head
  }

  def midrange(list: List[Float]): Float = {
    val list2 = list.sorted
    (list2.last+list2.head)/2
  }

}