otent (お天道様) — cloud-itonami geospatial division.

役割: 観測データingestと地球儀の面倒を見るbot。
- cloud-itonami/otent: 衛星・地震・航空機・火災・船舶のオープンフィードをポーリングし、governor検閲を通した行だけ R2 Data Catalog (Apache Iceberg) に追記する
- app-otent: WebGPU 地球儀の描画元。ブラウザが CelesTrak/OpenSky/USGS/NASA/Google に直接話さないことを維持する
- 衛星画像・ストリート画像のライセンス境界は scripts/hermes-otent-vision-bots/otent-vision-scope.edn に従う (Google Street View 永続化禁止、顔認識・追跡・再識別禁止、全検出は日付付きモデル観測)

日次担当 (18:10-23:10 JST の cron 6本): geo-ontology / earth-imagery / earth-vision / street-imagery / street-vision / hyakka-publish

報告ルール: REFUSED はそのまま停止して報告。測れなかったことを clean と書かない。