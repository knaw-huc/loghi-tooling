# Loghi tooling

This project contains the tools used by the Loghi framework.

## Compilation
### Setup langident
Make sure langident is installed:
```shell
git clone https://github.com/rvankoert/langident.git
cd langident
mvn clean package
mvn install:install-file -Dfile=target/langident-1.0.5-SNAPSHOT.jar -DgroupId=nl.knaw.huygens.pergamon.nlp -DartifactId=langident -Dpackaging=jar -Dversion=1.0.5
```
Compiling loghi-tooling
```shell
cd ../loghi-tooling
mvn clean package
```

## Minions
These are commandline tools that help process the input and output for the Loghi framework.

| Minion | Purpose | Key Inputs / Notes |
|--------|---------|--------------------|
| MinionCutFromImageBasedOnPageXMLNew | Cut text line image snippets based on PAGE XML coordinates | Image + PAGE XML folder (`page/`), supports styles & rescaling |
| MinionDetectLanguageOfPageXml | Predict document and region-level language | PAGE XML directory; optional training data folders |
| MinionExtractBaselines | Inject baselines/text lines into PAGE XML from segmentation PNG | PAGE XML + baseline PNG + config (P2PaLA/Laypa) |
| MinionExtractBaselinesStartEndNew | Baselines + start/end point images to improve rotated line detection | PAGE XML + baseline PNG + start/end PNGs |
| MinionExtractBaselinesStartEndNew3 | Updated rotated line baseline extraction (variant v3) | PAGE XML + baseline + start/end PNGs (new heuristics) |
| MinionConvertPageToTxt | Convert PAGE XML files to consolidated or line-based text | PAGE XML directory; flags for linebased/plaintext |
| MinionConvertToPdf | Produce a PDF from JPEG images, overlaying PAGE text at baselines | JPEG images + matching PAGE XML in `page/` subdir |
| MinionFixPageXML | Normalize/fix PAGE XML; optionally remove text or words | PAGE XML directory; namespace flag (2013/2019) |
| MinionGarbageCharacterCalculator | Compute percentage of disallowed characters | PAGE XML file + allowed characters file |
| MinionGeneratePageImages | Synthesize PAGE XML + rendered images from text and fonts | Text files + fonts directory |
| MinionLoghiHTRMergePageXML | Merge Loghi HTR output lines into existing PAGE XML | PAGE XML + HTR results file + config |
| MinionPyLaiaMergePageXML | Merge PyLaia HTR output lines into existing PAGE XML | PAGE XML + PyLaia results file |
| MinionRecalculateReadingOrderNew | Recompute reading order of regions in PAGE XML | PAGE XML directory (modifies in place) |
| MinionShrinkRegions | Shrink region polygons based on image content | Images + PAGE XML in `page/` subfolder |
| MinionShrinkTextLines | Shrink text line polygons based on image content | Images + PAGE XML in `page/` subfolder |
| MinionSplitPageXMLTextLineIntoWords | Derive word segmentation from text lines | PAGE XML directory |
| MinionConvertOCRResult | Convert OCR result files (ALTO -> PageXML2019 implemented) | Input directory of OCR XML; target format flag |

### MinionCutFromImageBasedOnPageXMLNew
This tool will cut the textlines from an image based on the [PAGE](https://github.com/PRImA-Research-Lab/PAGE-XML) xml file.
It expects that the PAGE xml file has same name as the image except for the extension.

#### Show help
```bash
./target/appassembler/bin/MinionCutFromImageBasedOnPageXMLNew -help
```

#### A typical call
```bash
./target/appassembler/bin/MinionCutFromImageBasedOnPageXMLNew -input_path /example/input_path -outputbase /example/output_path -output_type png -channels 4 -threads 5
```
This call will take the images from `/example/input_path`. 
It will take the page from `/example/input_path/page`.
The text lines will be stored as `png` in `/example/output_path` in a subfolder with the name of the original image.
The images will be stored with transparency information.
The minion will use 5 threads.

**OPTIONAL PARAMETER:** ```--use_tags``` for using HTML tag equivalents for the text styles taken from the pageXML: <br>
**IMPORTANT**: this only works if ```--include_text_styles``` and ```--write_text_contents``` are passed as well.
* ␅ underline
  * **before**: ␅u␅n␅d␅e␅r␅l␅i␅n␅e
  * **after**: <u>underline</u>
* ␃ strikethrough
  * **before**: ␃u␃n␃d␃e␃r␃l␃i␃n␃e
  * **after**: <s>underline</s>
* ␄ subscript;
  * **before**: H␄2O
  * **after**: H<sub>2</sub> O
* ␆ superscript
  * **before**: E=mc␆2
  * **after**: E=mc<sup>2</sup>

### MinionDetectLanguageOfPageXml
This minion will try to predict the language of the PAGE file.
If the PAGE file contains text regions it will also predict the language for each region.

#### Show help
```bash
./target/appassembler/bin/MinionDetectLanguageOfPageXml -help
```

#### A typical call
```bash
./target/appassembler/bin/MinionDetectLanguageOfPageXml -page /example/page -lang_train_data /example/lang_training_data
```

`/example/page` contains the PAGE xml files of which the language has to be detected.
`-lang_train_data` is an optional argument, but it is highly recommended, to use your own training data.
The training data looks something like this:
```
lang_training_data
|
- Dutch
- English
```

`Dutch` and `English` are plain text files. 
The names should comply with https://github.com/PRImA-Research-Lab/PAGE-XML/blob/4f40cd4b68d893b02a6396cf00df3e0e96db0d21/pagecontent/schema/pagecontent.xsd#L1675
The contents of the files should be a unicode text in the language of the file name.

### MinionExtractBaseLines
This minion takes PAGE XML and a PNG containing baseline labels (from P2PaLA or Laypa) and extracts baseline / text line information, adding it to the PAGE XML regions. Supports optional splitting of merged baselines and configuration via P2PaLA or Laypa config files.

Typical call (P2PaLA style input where baselines are label 0 or 255 depending on config):
```bash
./target/appassembler/bin/MinionExtractBaselines -input_path_png /example/p2pala/result/png \
  -input_path_page /example/p2pala/result/png \
  -output_path_page /example/output/page/
```

#### Show help
```bash
./target/appassembler/bin/MinionExtractBaseLines -help
```

#### A typical call for Laypa
```bash
./target/appassembler/bin/MinionExtractBaseLines -input_path_png /example/p2pala/result/png -input_path_page /example/p2pala/result/png -output_path_page /example/output/page/ -invert_image
```


#### A typical call for P2PALA
```bash
./target/appassembler/bin/MinionExtractBaseLines -input_path_png /example/p2pala/result/png -input_path_page /example/p2pala/result/png -output_path_page /example/output/page/
```
P2PaLA will output the page and pngs in the same folder.
So `input_path_png` and `input_path_page` will have the same value most of the time.

### MinionExtractBaselinesStartEndNew
This minion expects: 
 * pageXML, 
 * a folder with pngs containing the baselines
 * a folder with pngs containing the start points
 * a folder with pngs containing the end points
 * a folder to store the new PAGE xml
It extracts info about the baselines from the images and add baseline/textline information to the regions in the pagexml.
This version adds the ability to correctly detect rotated lines.

### MinionExtractBaselinesStartEndNew3
This minion expects pageXML, a png containing baselines and a png containing baseline start and ending as input.
It extracts info about the baselines from the images and add baseline/textline information to the regions in the pagexml.
This version adds the ability to correctly detect rotated lines.

#### Show help
```bash
./target/appassembler/bin/MinionExtractBaselinesStartEndNew3 -help
```

#### A typical call
```bash
./target/appassembler/bin/MinionExtractBaselinesStartEndNew3 -input_path_png /example/png_input/ -input_path_pagexml /example/page_input/ -output_path_pagexml /example/page_output/
```

### MinionConvertPageToTxt
Converts PAGE XML files in a directory to plain text (.txt). Can output either:
* Concatenated text (default) preserving paragraph/line breaks
* Line-based text (one line per PAGE TextLine) using `-linebased`
* Plain text vs UTF8 extracted text using `-plaintext`

Options:
* `-pagexmldir <dir>` (required) Directory containing PAGE XML files.
* `-no_overwrite` Do not overwrite existing .txt files.
* `-linebased` Output one line per PAGE text line.
* `-plaintext` Use `TextEquiv.PlainText` instead of UTF8 text.

Example:
```bash
./target/appassembler/bin/MinionConvertPageToTxt -pagexmldir /data/pagexml -linebased
```

### MinionConvertToPdf
Builds a PDF from a directory of JPEG images, overlaying PAGE XML textual content at baseline positions. It expects for each image a PAGE XML file in a `page/` subdirectory with the same base filename.

Usage:
```bash
./target/appassembler/bin/MinionConvertToPdf output.pdf /path/to/images
```
Notes:
* Font size auto-scales if x-height isn't available.
* Currently uses Times Roman and positions text at baseline start coordinates.

### MinionFixPageXML
This tool reads and then writes the PAGE XML again, fixing some small problems that exist in existing PAGE XML files.  

To fix a PAGE XML file, use the following command:
```bash
./target/appassembler/bin/MinionFixPageXML -input_path /path/to/input/pagexml -output_path /path/to/output/pagexml -namespace http://schema.primaresearch.org/PAGE/gts/pagecontent/2013-07-15
``` 

-input_path: Path to the directory containing the input PAGE XML files.
-namespace: Target namespace for the PAGE XML files. Default is http://schema.primaresearch.org/PAGE/gts/pagecontent/2019-07-15 
use http://schema.primaresearch.org/PAGE/gts/pagecontent/2013-07-15 for PAGE 2013 and compatibility with Transkribus.
-removetext: Optional flag to remove text from the PAGE XML.
-removewords: Optional flag to remove words from the PAGE XML.

Correct options are:
* `-input_path <dir>` (required) Path to PAGE XML files (recursive descent into subdirectories).
* `-removetext` Remove region and line level text (sets TextEquiv to null).
* `-removewords` Remove word elements from lines.
* `-namespace <ns>` Target namespace (default 2019: `http://schema.primaresearch.org/PAGE/gts/pagecontent/2019-07-15`). Use 2013: `http://schema.primaresearch.org/PAGE/gts/pagecontent/2013-07-15` for compatibility with Transkribus.

Example:
```bash
./target/appassembler/bin/MinionFixPageXML -input_path /data/pagexml -namespace http://schema.primaresearch.org/PAGE/gts/pagecontent/2013-07-15 -removewords
```


### MinionGarbageCharacterCalculator
This minion returns the characters that should not be in the text as a percentage of total amount of characters.

#### Show help
```bash
./target/appassembler/bin/MinionGarbageCharacterCalculator -help
```

#### A typical call
```bash
./target/appassembler/bin/MinionGarbageCharacterCalculator -page_file /path/to/page.xml -characters_file /path/supported_characters.txt
```

`/path/allowed_characters.txt` is a plain text file, that contains the supported without any spaces in between.
It should look something like:

```text
abcdefgABCDEFG
```


### MinionGeneratePageImages
The minion will create PAGE xml and the image, written in a font.

#### Show help
```bash
./target/appassembler/bin/MinionGeneratePageImages -help
```

#### A typical call
```bash
./target/appassembler/bin/MinionGeneratePageImages -text_path /example/text_path/ -output_path /example/page_output/ -font_path /example/fonts 
```
* `text_path` contains  plain text files
* `font_path` contains font files (i.e. *.ttf)

For each text a synthetic will be created with a randomly chosen font.

### MinionLoghiHTRMergePageXML
This minion merges the HTR results of [Loghi HTR](https://github.com/rvankoert/loghi-htr) with the existing PAGE files.
The results file should look something like:
```
/example/image_name/page_name.xml-line_name1.png     This is a text line
/example/image_name/page_name.xml-line_name2.png     This is a text line too
```

#### Show help
```bash
./target/appassembler/bin/MinionLoghiHTRMergePageXML -help
```

#### A typical call
```bash
./target/appassembler/bin/MinionLoghiHTRMergePageXML -input_path /example/page -results_file /example/htr_results.txt -config_file /path/to/htr-config.json
```

### MinionPyLaiaMergePageXML
This minion works similar to MinionLoghiHTRMergePageXML, but is made to process the HTR results of [Pylaia](https://github.com/jpuigcerver/PyLaia).

#### Show help
```bash
./target/appassembler/bin/MinionPyLaiaMergePageXML -help
```

#### A typical call
```bash
./target/appassembler/bin/MinionPyLaiaMergePageXML -input_path /example/page -results_file /example/htr_results.txt
```

### MinionRecalculateReadingOrderNew
This minion will recalculate the reading order of the PAGE xml files.
It will change the page files the folder that is passed as the `input_path_page`-argument.

#### Show help
```bash
./target/appassembler/bin/MinionRecalculateReadingOrderNew -help
```

#### A typical call
```bash
./target/appassembler/bin/MinionRecalculateReadingOrderNew -input_path_page /example/page/
```

### MinionShrinkRegions
This minion reevaluates the Text lines of the PAGE xml and shrinks them where needed.
The results are based on the image the PAGE xml is describing.

#### Show help
```bash
./target/appassembler/bin/MinionShrinkRegions -help
```

#### A typical call
```bash
./target/appassembler/bin/MinionShrinkRegions -input /example/images
```
`input` should be a folder and should contain the images and a subfolder `page` with the PAGE xml files of the images.

### MinionShrinkTextLines
This minion reevaluates the Text lines of the PAGE xml and shrinks them where needed. 
The results are based on the image the PAGE xml is describing.

#### Show help
```bash
./target/appassembler/bin/MinionShrinkTextLines -help
```

#### A typical call
```bash
./target/appassembler/bin/MinionShrinkTextLines -input /example/images
```
`input` should be a folder and should contain the images and a subfolder `page` with the PAGE xml files of the images. 

### MinionSplitPageXMLTextLineIntoWords
This minion will split the text lines of a PAGE xml file into words.

#### Show help
```bash
./target/appassembler/bin/MinionSplitPageXMLTextLineIntoWords -help
```

#### A typical call
```bash
./target/appassembler/bin/MinionSplitPageXMLTextLineIntoWords -input_path /example/page
```

### MinionConvertOCRResult
This tool converts OCR result files between several supported formats. It currently implements conversion from ALTO XML to PageXML 2019. Other target formats are planned or partially stubbed.

Supported source format auto-detection (per file):
* PageXML 2013 (PcGts root with 2013-07-15 namespace)
* PageXML 2019 (PcGts root with 2019-07-15 namespace)
* ALTO (root element <alto>)
* hOCR (HTML containing elements with class="ocr")

Target formats (argument to -targetformat / -t):
* PageXML2013 (planned; conversion logic commented out)
* PageXML2019 (implemented for ALTO source)
* hOCR (placeholder)
* ALTO (placeholder for reverse conversion)

How it works:
1. Reads every regular file in the input directory (non-recursive).
2. Detects its format using lightweight string matching (namespace checks, root tag, presence of hOCR markers).
3. For ALTO -> PageXML2019:
   * Parses ALTO XML using AltoUtils.readAltoDocumentFromString into an AltoDocument.
   * Converts AltoDocument -> DocumentPage -> PcGts (PageXML 2019) via DocumentTypeConverter.
   * Serializes PcGts with Jackson XmlMapper (pretty-printed) and writes to output directory, preserving the original filename.
4. Skips files with unknown or unsupported formats, printing a message to stderr.
5. Ensures the output directory exists (creates it if absent).

CLI options:
* -i / --inputpath    Path to directory containing OCR result files (default: /scratch/altotest/)
* -o / --outputpath   Path to directory for converted files (default: /tmp/limited/)
* -t / --targetformat Desired target format (case-insensitive). Default: PageXML2013

Example: Convert a batch of ALTO XML files to PageXML 2019
```bash
./target/appassembler/bin/MinionConvertOCRResult -inputpath /example/alto -outputpath /example/pagexml -targetformat PageXML2019
```

Notes & limitations:
* This is a work-in-progress tool. Expect incomplete functionality and breaking changes.
* Currently only ALTO -> PageXML2019 is fully implemented.
* PageXML 2013 -> PageXML 2019 conversion is indicated but commented out.
* hOCR and ALTO outputs are placeholders; invoking with those target formats will not perform conversions yet.
* Processing is non-recursive; organize files flat or invoke multiple times for subdirectories.
* Detection depends on simple string heuristics; malformed XML may be skipped.

Planned enhancements:
* Implement PageXML 2013 to 2019 conversion pathway.
* Add hOCR and ALTO generation (reverse conversions).
* Optional recursive directory traversal.
* More robust XML validation and error reporting.

## REST API

### Pipeline requests
These requests are on the public port of the webservice, that is by default `8080`.

#### Extract baselines
When running old P2PaLA (where 0 means baseline):
```bash
curl -X POST -F "mask=@/data/scratch/p2palaintermediate/5c52d146-34b1-48e8-8805-04885d39d96a.png" -F "xml=@/data/scratch/p2palaintermediate/5c52d146-34b1-48e8-8805-04885d39d96a.xml" -F "identifier=id" -F "invertImage=true" http://localhost:8080/extract-baselines
```
When running old P2PaLA with config file:
```bash
curl -X POST -F "mask=@/data/scratch/p2palaintermediate/5c52d146-34b1-48e8-8805-04885d39d96a.png" -F "xml=@/data/scratch/p2palaintermediate/5c52d146-34b1-48e8-8805-04885d39d96a.xml" -F "identifier=id" -F "invertImage=true" -F "p2palaa_config=@/path/to/p2pala_config.json"  -F "config_white_list=num_workers" -F "config_white_list=line_alg" http://localhost:8080/extract-baselines
```
When running Laypa (where 255 means baseline):
```bash
curl -X POST -F "mask=@/data/scratch/p2palaintermediate/5c52d146-34b1-48e8-8805-04885d39d96a.png" -F "xml=@/data/scratch/p2palaintermediate/5c52d146-34b1-48e8-8805-04885d39d96a.xml" -F "identifier=id" http://localhost:8080/extract-baselines
```
When running Laypa with config file:
```bash
curl -X POST -F "mask=@/data/scratch/p2palaintermediate/5c52d146-34b1-48e8-8805-04885d39d96a.png" -F "xml=@/data/scratch/p2palaintermediate/5c52d146-34b1-48e8-8805-04885d39d96a.xml" -F "identifier=id" -F "laypa_config=@/path/to/laypa_config.yml"  -F "config_white_list=DATASETS" -F "config_white_list=VERSION" http://localhost:8080/extract-baselines
```


Request with all options
```bash
curl -X POST -F "mask=@/data/scratch/p2palaintermediate/5c52d146-34b1-48e8-8805-04885d39d96a.png" -F "xml=@/data/scratch/p2palaintermediate/5c52d146-34b1-48e8-8805-04885d39d96a.xml" -F "identifier=id" -F "margin=30" http://localhost:8080/extract-baselines
```

#### CutFromImageBasedOnPageXMLNewResource
Simple request:
```bash
curl -X POST -F "image=@/data/scratch/p2palaintermediate/5c52d146-34b1-48e8-8805-04885d39d96a.png" -F "page=@/tmp/upload/id/extract_baselines.xml" -F "identifier=id" -F "output_type=png" -F "channels=4" http://localhost:8080/cut-from-image-based-on-page-xml-new
```
**optional parameter:** ```-F @use_tags=true``` for using HTML tag equivalents for the text styles taken from the pageXML: <br>
**IMPORTANT**: this only works if ```-F @include_text_styles=true``` and ```-F @write_text_contents=true``` are passed as well.
* ␅ underline
  * **before**: ␅u␅n␅d␅e␅r␅l␅i␅n␅e
  * **after**: <u>underline</u>
* ␃ strikethrough
  * **before**: ␃u␃n␃d␃e␃r␃l␃i␃n␃e
  * **after**: <s>underline</s>
* ␄ subscript;
  * **before**: H␄2O
  * **after**: H<sub>2</sub> O
* ␆ superscript
  * **before**: E=mc␆2
  * **after**: E=mc<sup>2</sup>

Full request:
```bash
curl -X POST -F "image=@/data/scratch/p2palaintermediate/5c52d146-34b1-48e8-8805-04885d39d96a.png" \
 -F "page=@/tmp/upload/id/extract_baselines.xml" \
 -F "identifier=id" \
 -F "output_type=png" \
 -F "channels=4" \
 -F "min_width=5" \
 -F "min_height=5" \
 -F "min_width_to_height_ratio=2" \
 -F "write_text_contents=true" \
 -F "rescale_height=20" \
 -F "output_box_file=false" \
 -F "output_txt_file=false" \
 -F "recalculate_text_line_contours_from_baselines=false" \
 -F "fixed_x_height=15" \
 -F "min_x_height=10" \
 -F "include_text_styles=true" \
  http://localhost:8080/cut-from-image-based-on-page-xml-new
```

#### LoghiHTRMergePageXMLResource
Simple request
```bash
curl -X POST -F "identifier=id" -F "page=@/home/martijnm/workspace/images/loghi-htr/data/page/NL-0400410000_26_009015_000321.xml" -F "results=@/home/martijnm/workspace/images/loghi-htr/results.txt" -F "htr-config=@/home/martijnm/workspace/images/loghi-htr/output/config.json" http://localhost:8080/loghi-htr-merge-page-xml
```

Full request
```bash
curl -X POST -F "identifier=id" -F "page=@/home/martijnm/workspace/images/loghi-htr/data/page/NL-0400410000_26_009015_000321.xml" -F "results=@/home/martijnm/workspace/images/loghi-htr/results.txt" -F "htr-config=@/home/martijnm/workspace/images/loghi-htr/output/config.json" -F "comment=My comment" -F "config_white_list=seed" -F "config_white_list=batch_size" http://localhost:8080/loghi-htr-merge-page-xml
```

#### RecalculateReadingOrderNewResource
Simple request
```bash
curl -X POST -F "identifier=id" -F "page=@/home/martijnm/workspace/images/loghi-htr/data/page/NL-0400410000_26_009015_000321.xml" -F "border_margin=200" http://localhost:8080/recalculate-reading-order-new
```
Full request
```bash
curl -X POST -F "identifier=id" -F "page=@/home/martijnm/workspace/images/loghi-htr/data/page/NL-0400410000_26_009015_000321.xml" -F "border_margin=200" -F "interline_clustering_multiplier=1.5" -F "dubious_size_width_multiplier=0.05" -F "dubious_size_width=1024" http://localhost:8080/recalculate-reading-order-new
```

#### SplitPageXMLTextLineIntoWordsResource

```bash
curl -X POST -F "identifier=id" -F "xml=@/home/stefan/Documents/repos/laypa/tutorial/data/inference/page/NL-HaNA_1.01.02_3112_0395.xml" http://localhost:8080/split-page-xml-text-line-into-words
```

#### DetectLanguageOfPageXmlResource
```bash
curl -X POST -F "identifier=id" -F "page=@/home/martijnm/workspace/images/loghi-htr/data/page/NL-040041000_26_009015_000321.xml" -F "training_data=@/home/martijnm/workspace/images/loghi-tooling/minions/src/main/resources/lang-ident-training-data/Dutch" -F "training_data=@/home/martijnm/workspace/images/loghi-tooling/minions/src/main/resources/lang-ident-training-data/English" -F "training_data=@/home/martijnm/workspace/images/loghi-tooling/minions/src/main/resources/lang-ident-training-data/French" http://localhost:8080/detect-language-of-page-xml
```

### Admin requests
These requests use the admin port that is by default `8081`.
These requests are GET requests and could be viewed in your browser.

#### Prometheus metrics
`http://localhost:8081/prometheus`

#### Admin overview
`http://localhost:8081/`
---
## Developer Notes
### Logging (SMELL-03/SMELL-04)
All production code uses SLF4J (`org.slf4j.Logger`). Never call `System.out`,
`System.err`, or `Throwable.printStackTrace()` in non-test code — use parametrised SLF4J:
```java
LOG.warn("Failed to process {}: {}", file, message);
LOG.error("Unexpected error while merging", e);
```
### XML factories (SEC-03 / OPT-02 / OPT-03)
`DocumentBuilderFactory` and `TransformerFactory` are expensive (classpath scanning) and
are also XXE attack surfaces. The codebase keeps **single, hardened, `static final`**
instances per module — see:
* `stringtools/StringTools` — `SECURE_DOCUMENT_BUILDER_FACTORY`, `SECURE_TRANSFORMER_FACTORY`
* `pagexmlutils/PageUtils` — `SECURE_TRANSFORMER_FACTORY`
* `layoutanalyzer/LayoutAnalyzer` — `SECURE_TRANSFORMER_FACTORY`
If you add a new XML factory anywhere, **harden it** with `disallow-doctype-decl=true`,
disable external entities/parameter-entities, and set
`ACCESS_EXTERNAL_DTD`/`ACCESS_EXTERNAL_SCHEMA`/`ACCESS_EXTERNAL_STYLESHEET=""`.
### Jackson mappers (SMELL-06)
Construct `ObjectMapper` / `XmlMapper` once and cache as `private static final`. They are
expensive to build and thread-safe to share after construction.
### OpenCV `Mat` lifecycle (OPT-01 / EXT-MAT-*)
Native off-heap memory is not reclaimed by the JVM GC. Every `new Mat()` /
`Imgcodecs.imread(...)` / `someMat.submat(...)` / `.clone()` **must** be matched by a
`release()` in a `finally` block.
Two helpers exist in `layoutanalyzer/.../layoutlib/`:
* **`OpenCVWrapper`** — wraps `Imgcodecs.imread`, `submat`, `clone`, allocation/release
  behind a synchronised lock so concurrent workers cannot trample the native allocator.
  **Always** route image reads through `OpenCVWrapper.imread(String filePath)` instead
  of `Imgcodecs.imread(...)` directly.
* **`MatScope`** — try-with-resources Mat tracker. Use it whenever a method allocates more
  than one transient Mat:
  ```java
  try (MatScope scope = MatScope.open()) {
      Mat gray  = scope.track(OpenCVWrapper.imread(path));
      Mat blur  = scope.track(OpenCVWrapper.newMat());
      Imgproc.GaussianBlur(gray, blur, ...);
      // ...
      // Both Mats are released in LIFO order when the scope is closed,
      // even on early return / exception.
  }
  ```
The `MatLeakRegressionTest` in `layoutanalyzer/src/test/java/.../MatLeakRegressionTest.java`
contains 11 regression tests covering MatScope, OpenCVWrapper, double-release detection,
submat+clone cleanup, stress loops and DocumentPage cache clearing. Add new tests there
whenever you add a new Mat-owning code path.
### Test runners
The `layoutanalyzer` module declares `<forkMode>always</forkMode>` in its Surefire config.
This is **required** because the OpenCV JNI library cannot be loaded twice in the same
JVM (`System.loadLibrary` is one-shot), and multiple test classes load the native library
in `@BeforeClass`. Do not change this without first eliminating the per-class native lib
load.
### Joda-Time has been removed
All four formerly-affected modules now use `java.time`. PageXML timestamps without an
offset are parsed as **UTC** in order to round-trip exactly with Jackson's UTC default.
Do not reintroduce `org.joda.time.*`.
