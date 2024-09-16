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
This minion takes the output of [P2PaLA](https://github.com/rvankoert/P2PaLA) or [Laypa](https://github.com/knaw-huc/laypa) and uses to update the PAGE xml files of the images with the coordinates of the baselines.

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

#### Show help
```bash
./target/appassembler/bin/MinionExtractBaselinesStartEndNew -help
```

#### A typical call
```bash
./target/appassembler/bin/MinionExtractBaselinesStartEndNew3 -input_path_png /example/png_input/ -input_path_pagexml /example/page_input/ -input_path_png_start /example/input_png_start/ -input_path_png_end /example/input_png_end/ -output_path_pagexml /example/page_output/
```

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
