"""
Makes AustralianFoodData.kt from the Australian Food Composition Database (AFCD) of Food Standards
Australia New Zealand, for the food search of the Smart Meal dialog.

Download "AFCD Release 3 - Nutrient profiles.xlsx" from https://www.foodstandards.gov.au/data-files, then:

    python3 ui/tools/afcd_to_kotlin.py "AFCD Release 3 - Nutrient profiles.xlsx" > \
        ui/src/commonMain/kotlin/app/aaps/ui/compose/smartMealDialog/food/AustralianFoodData.kt

Only the Python standard library is needed.
"""
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

NS = {'m': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
# Columns of the nutrient profile sheets, per 100 g or per 100 mL.
NAME, PROTEIN, FAT, FIBRE, CARBS = 3, 7, 9, 11, 38
# A Kotlin string constant may be at most 65535 bytes.
CHUNK_BYTES = 60000


def rows(z, shared, sheet):
    def col(ref):
        n = 0
        for ch in re.match(r'[A-Z]+', ref).group():
            n = n * 26 + ord(ch) - 64
        return n - 1
    for r in ET.fromstring(z.read(sheet)).iter('{%s}row' % NS['m']):
        out = {}
        for c in r.findall('m:c', NS):
            v = c.find('m:v', NS)
            if c.get('t') == 'inlineStr':
                out[col(c.get('r'))] = ''.join(x.text or '' for x in c.iter('{%s}t' % NS['m']))
            elif v is not None:
                out[col(c.get('r'))] = shared[int(v.text)] if c.get('t') == 's' else v.text
        if out:
            yield [out.get(i, '') for i in range(max(out) + 1)]


def num(text):
    try:
        v = round(float(text), 1)
    except ValueError:
        return '0'
    return str(int(v)) if v == int(v) else str(v)


def main(path):
    z = zipfile.ZipFile(path)
    shared = [''.join(t.text or '' for t in si.iter('{%s}t' % NS['m']))
              for si in ET.fromstring(z.read('xl/sharedStrings.xml')).findall('m:si', NS)]
    lines = []
    # Sheet 2 is per 100 g, sheet 3 per 100 mL; the first two rows of each are titles.
    for sheet, liquid in (('xl/worksheets/sheet2.xml', False), ('xl/worksheets/sheet3.xml', True)):
        for r in list(rows(z, shared, sheet))[2:]:
            name = ' '.join(r[NAME].split())
            if not name or any(c in name for c in '|$"\\'):
                continue
            fields = [name, num(r[CARBS]), num(r[FIBRE]), num(r[PROTEIN]), num(r[FAT])]
            if liquid:
                fields.append('l')
            lines.append('|'.join(fields))
    chunks, cur = [], []
    for line in lines:
        if sum(len(x.encode()) + 1 for x in cur) + len(line.encode()) > CHUNK_BYTES:
            chunks.append(cur)
            cur = []
        cur.append(line)
    chunks.append(cur)

    print('package app.aaps.ui.compose.smartMealDialog.food')
    print()
    print('// Made by ui/tools/afcd_to_kotlin.py. Do not edit by hand.')
    print('//')
    print('// Australian Food Composition Database - Release 3, (c) Food Standards Australia New Zealand,')
    print('// https://www.foodstandards.gov.au/science-data/food-nutrient-databases/afcd, used under the')
    print('// Creative Commons Attribution 4.0 licence. Only the name, available carbohydrate, fibre, protein')
    print('// and fat of each food are kept. FSANZ does not endorse this app.')
    print()
    print('/**')
    print(' * One food per line: name|carbs|fibre|protein|fat, grams per 100 g. A last field "l" means per 100 mL.')
    print(' * Carbs are available carbohydrate without sugar alcohols, as on Australian labels.')
    print(' */')
    print('internal val australianFoodData: List<String> = listOf(')
    for i, chunk in enumerate(chunks):
        print('    """')
        for line in chunk:
            print(line)
        print('"""' + (',' if i < len(chunks) - 1 else ''))
    print(')')


if __name__ == '__main__':
    main(sys.argv[1])
