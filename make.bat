xas99.py -i -q -R -L elite.lst -E symbols.txt src/elite.a99 -o bin/elite

xas99.py src/rom-banks/rom-bank-4.a99 -B -q -R -o bin/bank4.bin
xas99.py src/rom-banks/rom-bank-5.a99 -B -q -R -o bin/bank5.bin

java -jar tools/ea5tocart.jar bin\elite "elite"

copy /b bin\elite8.bin ^
    + bin\bank4.bin ^
    + bin\bank5.bin ^
    + empty.bin ^
    + empty.bin ^
    elite8.bin

jar -cvf elite.rpk elite8.bin layout.xml > make.log

