xas99.py -i -q -R -L elite.lst -E symbols.txt src/elite.a99 -o bin/elite

java -jar tools/ea5tocart.jar bin\elite "elite"

copy bin\elite8.bin .

jar -cvf elite.rpk elite8.bin layout.xml > make.log

