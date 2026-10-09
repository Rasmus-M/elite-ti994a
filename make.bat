xas99.py -i -q -R -L elite.lst -E symbols.txt src/elite.a99 -o bin/elite

xas99.py src/math-tables-bin.a99 -b -q -R -o bin/math-tables.bin
xas99.py src/code-bank-7.a99 -B -q -R -o bin/bank7.bin
xas99.py src/code-bank-8.a99 -B -q -R -o bin/bank8.bin
xas99.py src/code-bank-9.a99 -B -q -R -o bin/bank9.bin

xas99.py src/rom-banks/rom-bank-4.a99 -B -q -R -o bin/bank4.bin
xas99.py src/rom-banks/rom-bank-5.a99 -B -q -R -o bin/bank5.bin
xas99.py src/rom-banks/rom-bank-6.a99 -B -q -R -o bin/bank6.bin

java -jar tools/ea5tocart.jar bin\elite "elite demo"

copy /b bin\elite8.bin ^
    + bin\bank4.bin ^
    + bin\bank5.bin ^
    + bin\bank6.bin ^
    + bin\bank7.bin ^
    + bin\bank8.bin ^
    + bin\bank9.bin ^
    + empty.bin ^
    + empty.bin ^
    + empty.bin ^
    + empty.bin ^
    + empty.bin ^
    + empty.bin ^
    elite8.bin

jar -cvf elite.rpk elite8.bin layout.xml > make.log

