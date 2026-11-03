package app.aaps.ui.compose.smartMealDialog.food

// Made by ui/tools/afcd_to_kotlin.py. Do not edit by hand.
//
// Australian Food Composition Database - Release 3, (c) Food Standards Australia New Zealand,
// https://www.foodstandards.gov.au/science-data/food-nutrient-databases/afcd, used under the
// Creative Commons Attribution 4.0 licence. Only the name, available carbohydrate, fibre, protein,
// fat and total sugars of each food are kept. FSANZ does not endorse this app.

/**
 * One food per line: name|carbs|fibre|protein|fat|sugars, grams per 100 g; sugars may be empty when not known.
 * A last field "l" means per 100 mL. Carbs are available carbohydrate without sugar alcohols, as on Australian labels.
 */
internal val australianFoodData: List<String> = listOf(
    """
Cardamom seed, dried, ground|34.4|28|10.8|6.7|4.4
Cinnamon, dried, ground|27.5|53.1|4|1.2|2.2
Chilli (chili), dried, ground|12.9|34.2|12.3|14.3|7.2
Cloves, dried, ground|31.6|33.9|6|13|2.4
Coriander seed, dried, ground|8.4|41.9|12.4|17.8|2.4
Cumin (cummin) seed, dried, ground|33.7|10.5|17.8|22.3|2.2
Curry powder|2.8|53.2|14.3|14|2.8
Fenugreek seed, dried|4.2|47.6|23|6.4|0.6
Ginger, dried, ground|57.5|14.1|9|4.2|3.4
Mustard powder|17.2|5|29.4|39.5|5
Nutmeg, dried, ground|28.5|20.8|5.8|36.3|3
Oregano, dried|26.4|42.5|9|4.3|4.1
Paprika, dry powder|19.1|34.9|14.1|12.9|10.3
Pepper, black, ground|38.6|25.3|10.4|3.3|0.6
Rosemary, dried|20.4|42.6|4.9|15.2|19.6
Sage, dried|20.4|40.3|10.6|12.8|1.7
Thyme, dried, ground|26.9|37|9.1|7.4|1.7
Turmeric, dried, ground|44.4|22.7|9.7|3.2|3.2
Salt substitute, potassium chloride|0|0|0|0|0
Salt, table, iodised|0|0|0|0|0
Salt, table, non-iodised|0|0|0|0|0
Stock, dry powder or cube|22.5|0|11.3|8.9|4.3
Stock, liquid, all flavours (except fish), prepared from commercial powder or cube|0.4|0|0.2|0.2|0.1
Taco seasoning mix, chilli-based|58.1|8.7|6.2|4.8|28.5
Baking powder, dry powder|19.8|0.4|0.8|0.3|0
Baking soda (bicarbonate), dry powder|0|0|0|0|0
Cream of tartar, dry powder|0|0.2|0|0|0
Lecithin, soy, granules|4.9|15.1|6|62.4|4.4
Gelatine, all types|0|0|84.4|0.4|0
Vanilla, artificial or imitation|14.4|0|0|0|14.4
Vanilla bean extract|12.6|0|0.1|0.1|12.6
Yeast, dry powder|6.3|26.9|36.4|5|0
Beer, high alcohol (5% v/v & above)|2.1|0|0.5|0|0.2
Beer, full strength (alcohol 4-4.9% v/v)|2.3|0|0.4|0|0
Beer, full strength (alcohol 4-4.9% v/v), carbohydrate modified|1|0|0.3|0|0
Beer, mid-strength (alcohol 3-3.9% v/v)|2.2|0|0.3|0|0
Beer, light (alcohol 1-2.9% v/v)|2.2|0|0.2|0|0
Alcoholic beverage, spirit, ~30% v/v, all (Brandy, Gin, Rum, Vodka and Whisky), cooked|0.3|0|0|0|0.3
Alcoholic beverage, spirit, ~40% v/v, all (Brandy, Gin, Rum, Vodka and Whisky)|0.1|0|0|0|0.1
Cider, apple (alcohol ~4-5% v/v)|5.6|0|0|0|5.6
Mirin|26.6|0|0.3|0|26.6
Wine, red|0.2|0|0.2|0|0.2
Wine, red, cabernet sauvignon|0.2|0|0.3|0|0.2
Wine, red, merlot|0.4|0|0.3|0|0.4
Wine, red, pinot noir|0.2|0|0.2|0|0.2
Wine, red, shiraz|0.2|0|0.2|0|0.2
Wine, red, sparkling|2|0|0.2|0|2
Wine, red, cooked|0.2|0|0.3|0|0.2
Wine, rose|0.7|0|0.2|0|0.7
Wine, white, sauvignon blanc|0.1|0|0.2|0|0.1
Wine, white, semillon|0.2|0|0.2|0|0.2
Wine, white, chardonnay|0.3|0|0.3|0|0.3
Wine, white, riesling|0.4|0|0.2|0|0.4
Wine, white|0.2|0|0.3|0|0.2
Wine, white, cooked|0.3|0|0.2|0|0.3
Wine, white, sparkling|1|0|0.2|0|1
Wine, white, sweet dessert style|14.7|0|0.4|0|14.7
Wine, fortified, port|12.4|0|0.2|0|12.4
Wine, fortified, sherry, dry style (~1% sugars)|1.2|0|0.1|0|1.2
Wine, fortified, sherry, sweet style (~11% sugars)|11.1|0|0.2|0|11.1
Beverage base, chocolate flavour, added vitamins & minerals (Milo)|60.1|6.8|12.4|9.9|43.7
Beverage base, chocolate flavour, unfortified (Nesquik brand)|83.2|5.8|5.3|2.8|81
Beverage base, drinking chocolate, unfortified|76.3|1.5|5.6|4.8|74.3
Cocoa powder|10.5|27.7|22.1|15.2|1.5
Beverage, chocolate flavour, from Milo powder, with regular fat cow's milk|10.2|0.6|4.1|4|8.8
Beverage, chocolate flavour, from Nesquik powder, with regular fat cow's milk|9.7|0.3|3.4|3.4|9.6
Beverage, chocolate flavour, from drinking chocolate, with regular fat cow's milk|10.2|0.1|3.5|3.5|10.1
Protein powder, hemp seed based|4.4|27.8|35.8|13.5|4.2
Protein powder, whey based, protein >70%, unfortified|1.6|1.8|85|2.2|1.3
Protein powder, whey based, protein >70%, unfortified, prepared with water|0.3|0.3|14.2|0.4|0.2
Coffee, instant, dry powder or granules|3.6|20.6|18|0.3|3.4
Coffee, instant, dry powder or granules, decaffeinated|3.6|20.6|18|0.3|3.4
Coffee mix, with beverage whitener & sugar, dry powder|56.1|3.6|13.1|12.7|38.3
Coffee, prepared from coffee mix with sugar & whitener, no added milk|2.8|0.2|0.6|0.6|1.9
Coffee, black, from instant coffee powder|0.2|0.1|0.2|0|0
Coffee, black, from instant coffee powder, decaffeinated|0.2|0.1|0.2|0|0
Coffee, espresso, from ground coffee beans|0|0|1.7|0.3|0
Coffee, long black, from ground coffee beans|0|0|0.3|0.1|0
Coffee, flat white/latte/cappuccino, from ground coffee beans, with regular fat cow's milk|3.4|0|2.8|2.6|3.4
Cordial base, 25% citrus fruit juice, regular|37.3|0|0|0|37.3
Cordial base, 40% citrus fruit juice, regular|40.2|0|0.2|0|40.2
Cordial base, blackcurrant juice, regular|45|0|0.1|0|45
Cordial base, fruit juice/flavours, intense sweetened or diet|7.1|0|0|0|7.1
Cordial, 25% citrus fruit juice, regular, recommended dilution|8.7|0|0|0|8.7
Cordial, 40% citrus fruit juice, regular, recommended dilution|9.4|0|0|0|9.4
Cordial, blackcurrant juice, regular, recommended dilution|9.3|0|0|0|9.3
Cordial, fruit juice/flavours, recommended dilution|6.5|0|0|0|6.5
Cordial, fruit juice/flavours, intense sweetened or diet, recommended dilution|0.7|0|0|0|0.7
Juice, apple, commercial, added vitamin C|7|0.2|0.2|0|7
Juice, apple & blackcurrant, commercial|9.3|0|0.2|0|9.3
Juice, lemon|1.8|2|0.6|0.1|1.8
Juice, lime|1.2|3.6|0.8|0.1|1.2
Juice, orange, commercial|6.8|0.3|0.8|0|6.8
Juice, orange & mango, commercial|7.7|0.2|0.6|0|7.7
Juice, vegetable blend, commercial|2.7|0.6|0.9|0|2.1
Fruit drink, apple juice|8.2|0|0.1|0|7.8
Fruit drink, blackcurrant, commercial|9.8|0|0.2|0|9.8
Fruit drink, cranberry|12.2|0.1|0|0|11.3
Fruit drink, orange juice|9.6|0.1|0.2|0|9.6
Soft drink, cola flavour|12.3|0|0|0.2|12.3
Soft drink, cola flavour, decaffeinated|12.3|0|0|0.2|12.3
Soft drink, cola flavour, intense sweetened or diet|0|0|0|0|0
Soft drink, cola flavour, intense sweetened or diet, decaffeinated|0|0|0|0|0
Soft drink, dry ginger ale or ginger beer, regular|9.8|0|0|0|9.8
Soft drink, fruit flavours|8.8|0|0|0|8.8
Soft drink, tonic water|8.8|0|0|0|8.8
Soft drink, fruit flavours, intense sweetened or diet|0|0|0|0|0
Soft drink, tonic water, intense sweetened or diet|0|0|0|0|0
Soft drink, energy drink, Red Bull|10.8|0|0.1|0|10.8
Soft drink, energy drink, V|10.8|0|0.1|0|10.8
Kombucha, flavoured, added juice or intense sweeteners|0.9|0.1|0|0|0.8
Tea, regular, black, brewed from leaf or teabags, without milk|0|0|0.1|0|0
Tea, black, brewed from leaf or teabags, decaffeinated|0|0|0.1|0|0
Tea, green, plain, without milk|0|0|0.2|0|0
Mineral water, natural, unflavoured|0|0|0|0|0
Mineral water, citrus flavoured|3.7|0|0|0|3.7
Water, bottled, still|0|0|0|0|0
Water, soda|0|0|0|0|0
Water, tap|0|0|0|0|0
Biscuit, savoury, corn cake, plain, salted|71.5|5.9|9.8|3|0.4
Biscuit, savoury, rice cracker, plain|78.8|0.9|7|4.3|0.7
Biscuit, savoury, rice cracker, added vegetable powder|73.8|2|6.6|7.4|4.9
Biscuit, savoury, rice cake, from brown rice, plain|76.3|3.5|8.8|3.5|0.8
Biscuit, savoury, rice cracker, flavoured (excluding seaweed)|74.1|1.5|7.1|6.7|0.2
Biscuit, savoury, rice cracker, seaweed flavoured|75.3|1.6|9.6|1.2|1.7
Biscuit, savoury, from rye flour, crispbread|60.6|16.5|8.8|1.3|2.8
Biscuit, savoury, seed based|6|14.6|20.1|49.6|1.4
Biscuit, savoury, from white wheat flour, flaky cracker style|63.6|3|11.5|20.7|3.6
Biscuit, savoury, from white wheat flour, plain snack cracker style|66.3|3.4|8.1|20.7|6.7
Biscuit, savoury, from white wheat flour, Salada style|63.1|3.6|9.2|14.2|1.2
Biscuit, savoury, from white wheat flour, water cracker style|69.4|3.7|10.4|7.2|2
Biscuit, savoury, from white wheat flour, cheese-flavoured|56.4|2.9|12|24.2|3.2
Biscuit, savoury, from white wheat flour, flavoured (excluding cheese)|59.7|2|9.6|24.5|5.2
Biscuit, savoury, from wholemeal wheat flour, crispbread|59.1|9.9|10.6|8.1|2
Biscuit, savoury, from wholemeal wheat flour & rye flour, crispbread, puffed|69.1|7.6|14|3.8|1.5
Biscuit, savoury, from wheat flour, crispbread, puffed & toasted|66.1|3.3|12.3|5.6|3.6
Biscuit, sweet, Anzac style, commercial|60.3|3.8|5.8|22.1|32
Biscuit, sweet, Anzac style, homemade from basic ingredients|64|3.1|4.6|22.6|41.2
Biscuit, sweet, ginger flavoured, commercial|77.7|1.9|4.8|10.6|39
Biscuit, sweet, plain|69.4|2.1|6.4|15.8|24.7
Biscuit, sweet, plain, with icing, commercial|77.5|1.7|4.6|11.2|44.9
Biscuit, sweet, shortbread style, commercial|60.8|2.3|6.2|26.3|20.4
Biscuit, sweet, wheatmeal|67|5.6|7.8|11.7|21.6
Biscuit, sweet, biscuit base, caramel filling, chocolate-coated, commercial|61.8|1.8|5.6|19.7|45.5
Biscuit, sweet, biscuit base, mint filling, chocolate-coated|60.6|0.6|6.3|29.4|42
Biscuit, sweet, chocolate chip or coated|61.9|3|5.8|22.7|35.8
Biscuit, sweet, chocolate chip, homemade from basic ingredients, fat not further defined|66|1|5.1|21.7|40.7
Biscuit, sweet, chocolate flavoured, commercial|72.4|1.9|6.2|17.2|31
Biscuit, sweet, cream filled, commercial|62.8|2.7|5.4|21.5|33.6
Biscuit, sweet, sandwich, cream & jam filling|64.1|1.8|4|22.5|33.5
Biscuit, sweet, sandwich, cream filling, chocolate-coated, tim tam style|63.1|2.2|5.4|25.4|44.7
Biscuit, sweet, breakfast style, with or without dried fruit|64.6|6.4|7.5|15.6|24.1
Cone, wafer style, for ice cream|79.7|3.2|9.1|4|2.6
Bread, flat (pita or Lebanese), white, commercial|49.1|3.7|9.5|2.1|3.1
Bread, flat (pita or Lebanese), wholemeal, commercial|47.2|6.4|8.6|2|3.1
Bread, Naan, commercial|47.8|2.6|8|7.1|6
Bread, tortilla, white, commercial|46.3|2|7.3|9|4.3
Bread, wrap, white, commercial|46.9|3|7.8|8.4|5.3
Bread, flat wrap or tortilla, corn, commercial|44.8|5|4.8|3.2|4.5
Breadcrumbs, white|68.5|4.7|14|4.1|4.6
Bagel, from white flour, commercial|50.7|3.1|10.4|1.4|6.2
Bagel, from white flour, commercial, toasted|57.6|3.5|11.8|1.6|7
Bread, damper, from white flour, homemade|42.9|3.3|7.5|5.5|2.9
Bread, damper, homemade, toasted|50.5|3.9|8.8|6.5|3.4
Bread, from white flour|46.2|3.2|9.5|2|3.1
Bread, from white flour, toasted|54.3|4.3|10.8|2.8|3.7
Bread, from white flour, Italian-style, commercial|46.8|3.9|9|2.5|2
Bread, from white flour, Italian-style, commercial, toasted|55.7|4.6|10.7|3|2.4
Bread, from white flour, Turkish|45.5|3.6|7.8|2.8|2.2
Bread, from white flour, Turkish, toasted|54.2|4.3|9.2|3.3|2.6
Bread, from white flour, sour dough, commercial|42.9|3.1|8.8|1.4|1.2
Bread, from white flour, sour dough, toasted|51.1|3.7|10.4|1.7|1.4
Bread, organic|40.3|5.6|11.6|2.9|1.3
Bread, organic, toasted|47.4|6.6|13.7|3.4|1.5
Bread, pizza base, commercial|48.5|2.7|8.6|3.3|3.7
Bread roll, from white flour|44.6|3|9.3|2.9|3.4
Bread, from wholemeal flour|39.2|6.9|10.4|2.5|3.2
Bread, from wholemeal flour, toasted|46.1|8.1|12.2|2.9|3.7
Bread roll, from wholemeal flour|45.2|5.8|10.1|3|3
Bread, mixed grain|39.8|5.8|10.7|4.5|2.5
Bread, mixed grain, toasted|46.8|6.9|12.6|5.3|3
Bread roll, mixed grain|40.3|5.6|12.2|3|2.3
Bread, from rye flour, sour dough|42.7|5.2|9.2|1.6|2
Bread, from rye flour, sour dough, toasted|50.2|6.1|10.8|1.9|2.4
Bread, from white flour, added fibre|43.6|6.4|9.7|1.7|2.5
Bread, from white flour, added fibre, toasted|51.9|7.7|11.5|2|3
Bread, from white flour, added omega-3 polyunsaturates|46.6|3.1|9.1|2.1|2.4
Bread, from white flour, added omega-3 polyunsaturates, toasted|54.2|3.6|10.6|2.4|2.8
Bread, from white Jackaroo flour, added fibre and vitamins B1 & folate & Fe|45.7|6.5|8.3|2.3|3.8
Bread, from white Jackaroo flour, added fibre and vitamins B1 & folate & Fe, toasted|53.8|7.6|9.8|2.7|4.5
Bread, gluten free|36.6|3.9|5.5|3.9|2.6
Bread, gluten free, toasted|43.6|4.6|6.5|4.6|3.1
Bread, garlic, commercial, baked|42.8|3|8|15.1|3.4
Bread, garlic or herb, homemade, cooked|40.5|3.6|8.8|11.4|3.2
Bread roll, topped with cheese|35.8|2.4|12.4|9|2.8
Bread roll, topped with cheese & bacon|27|1.8|16.6|11.2|2.3
Bread, brioche|46.5|2.4|9.5|10.6|11.8
Bread, from white flour, commercial, added dried fruit|47.3|3.6|8.8|3.7|18.1
Bread, from white wheat flour, added dried fruit, toasted|52.6|4|9.8|4.1|20.1
Bun, sweet, hot cross bun, with dried fruit|50.2|4.1|7.2|3.2|21
Bun, sweet, with dried fruit, uniced|55.2|3|6.5|5|22.3
Bun, sweet, with dried fruit, iced|51.8|1.9|6.4|6.1|18.3
Crumpet, from white flour, toasted|43.3|3|7.1|1.2|2.6
Muffin, English style, from white flour|38.7|3.2|10.1|2.1|1.7
Muffin, English style, from white flour, toasted|42.1|3.4|11|2.3|1.8
Muffin, English style, from white flour, added dried fruit, toasted|46.4|3.6|9.7|2|11.6
Breakfast cereal, beverage, non-chocolate flavours, added vitamins & minerals (Up & Go)|12.4|0.8|3.1|1.5|7
Breakfast cereal, flakes of corn, unfortified|75.6|4.6|7.3|2.9|7.7
Breakfast cereal, flakes of corn, added vitamins & minerals (Kellogg's Cornflakes)|81.8|3.8|7.9|1.8|8.9
Breakfast cereal, mixed grain (wheat, rice & oat), flakes, honey, unfortified (Be Natural Honey Flakes)|65|12.5|8.4|4.1|11.9
Breakfast cereal, mixed grain (rice & wheat), flakes, added vitamins & minerals (Kellogg's Special K)|70.5|2.7|18.2|4.1|13.2
Breakfast cereal, mixed grain (wheat, oat & corn), added vitamins & minerals (Kellogg's Nutri-grain)|62.8|2.5|23.3|2.5|28
Breakfast cereal, mixed grain (wheat & oat), flakes, apricot & sultana, added vitamins & minerals (Kellogg's Just Right)|71.6|8.2|9.1|1.5|25.3
Breakfast cereal, puffed or popped rice, no added sugar or salt, unfortified|77.6|2.3|8.5|3|0.6
Breakfast cereal, puffed or popped rice, added vitamins & minerals (Kellogg's Rice Bubbles)|81.2|0.9|6.8|1.1|6.2
Breakfast cereal, puffed or popped rice, cocoa coating, added vitamins & minerals (Kellogg's Coco Pops)|83.2|1.9|5.3|2.9|30.4
Breakfast cereal, whole wheat, puffed, no added sugar or salt, unfortified|62.4|9.2|10|2.8|0.8
Breakfast cereal, whole wheat, biscuit, no added sugar, unfortified|63.7|11.5|10.4|2.7|1.1
Breakfast cereal, wheat bran, pellets, added vitamins & minerals (Kellogg's All Bran)|41.7|27.3|14.2|5|15.4
Breakfast cereal, wheat bran, flakes, added vitamins & minerals (Kellogg's Bran Flakes)|58.4|19.3|12.2|3.6|11
Breakfast cereal, whole wheat, biscuit, added vitamins & minerals (Sanitarium Weet Bix)|61|11|11.9|2.3|2.4
Breakfast cereal, whole wheat, biscuit, bran, added vitamins & minerals (Sanitarium Weet-Bix Hi-Bran)|56.5|17.5|12.5|5.6|7.9
Breakfast cereal, whole wheat, flakes, added vitamins & minerals (Weeties style)|65|11.7|11.5|2|3.4
Breakfast cereal, whole wheat, flakes, dried fruit & nuts, added vitamins & minerals (Uncle Toby's Fibre Plus)|65.6|15.5|9.1|3.3|25
Breakfast cereal, wheat bran, flakes, sultanas, added vitamins & minerals (Kellogg's Sultana Bran)|63.3|14.8|9.8|1.5|24.5
Muesli, granola, toasted, added nuts & seeds, unfortified|52.3|7.6|11.2|17.6|16.3
Muesli, granola, non-oat based, toasted, added nuts & seeds, unfortified|53.5|6.6|11.9|18.8|8.7
Muesli, toasted, added dried fruit & nuts, unfortified|56.6|11.4|8.9|13.3|21.7
Muesli, untoasted or natural style, added dried fruit, unfortified|59.2|9.4|10.8|10.8|18.7
Porridge, rolled oats, prepared with water|11.4|2.9|3|2.9|0.3
Porridge, rolled oats, prepared with regular fat cow's milk|14.5|1.6|5.3|4.9|5.3
Porridge, rolled oats mixed with sugar or honey & other flavours, prepared with cow's milk|19.8|1.7|4.6|4.4|9.8
Bread, banana, commercial|43.1|1.5|5.7|11.1|25.1
Bread, banana, homemade|50|1.3|5.3|12.7|27.4
Slice, brownie, chocolate, without nuts, commercial|45.6|2.6|7.1|23.1|36.3
Slice, brownie, chocolate, with nuts, homemade|52.7|1.9|6.5|25.5|35.8
Slice, caramel|52.4|0.8|5.3|24.6|41.8
Cake mix, plain, dry powder|73.5|1.4|4.8|8.2|42.5
Cake, carrot, commercial, iced|47.6|1.5|4.4|20.4|30.5
Cake, carrot, homemade, uniced|45.3|2.1|4.8|16|31.3
Cake, carrot, homemade, iced|49.5|1.7|3.9|15|37.8
Cake, chocolate, homemade, uniced|41.3|2.9|7.9|19.4|17.9
Cake, chocolate, homemade, iced|46.4|2.6|6.6|18|27
Cake, fruit, homemade, uniced|49.4|2.8|3.8|10|40.1
Cake, lamington, unfilled|48|2.4|4.7|9.4|31.5
Cake, mud, dark chocolate, commercial, chocolate ganache icing|48.7|2.6|5.1|15.6|33.1
Cake, mud, dark chocolate, homemade, uniced|32.5|0.5|5.6|34.6|25.5
Cake, mud, dark chocolate, homemade, chocolate ganache icing|35.5|0.6|5.2|34.1|28.3
Cake, plain butter cake, commercial, uniced|46.9|1.3|8.2|15.2|25.3
Cake, plain butter cake, commercial, iced|52.7|1.4|5|16.1|36.7
Cake, plain butter cake, homemade, uniced|52.5|0.9|6.7|16.3|23.5
Cake, plain butter cake, homemade, iced|55.2|0.7|5.4|15.2|31.5
Cake, sponge, plain, commercial, uniced, unfilled|56.5|1.2|7.5|8.2|32.5
Cake, sponge, plain, commercial, uniced, filled with jam & cream|58|1.2|6.7|12.2|37.6
Cake or cupcake, sponge, plain, commercial, iced, unfilled|61.6|0.7|5|11|46.4
Muffin, cake-style, berry, commercial|44.4|1.9|5.2|15.6|26.1
Muffin, cake-style, chocolate chip, commercial|48.1|1.4|6.4|17.7|29.5
Doughnut, dusted with cinnamon & sugar|43.9|2|6.6|19.5|13.7
Doughnut, iced|48.2|1.7|6.7|15.9|22.9
Doughnut, jam filled, sugar coated|46.2|1.9|6|17.6|19
Pancake, plain, homemade|30.3|0.8|7.4|7.6|4.2
Pikelet, plain, homemade|47.9|1.7|7.5|5.9|13.4
Pikelet or pancake, sweet, commercial|38.4|2.1|5.7|4.3|13.8
Pudding, chocolate, homemade|37|1.1|3.6|2.6|23.4
Pudding, plum, steamed or boiled, homemade|54.1|2.4|4.3|11.1|41.1
Pudding, sticky date, homemade|48.5|1.6|3|16.8|38.6
Scone, plain, commercial|55.2|2.1|7.9|8.5|6.5
Scone, plain, homemade|50.7|1.4|8|9.5|6.8
Scone, fruit, commercial|59.9|2.5|6.4|6.6|23.6
Scone, pumpkin, homemade|47.9|1.7|7.4|7.4|9.3
Amaranth, grain, whole, uncooked|55.6|11.1|15.2|6.4|3.8
Barley, pearl, uncooked|60.6|13.1|9.9|3.1|1
Barley, pearl, boiled, no added fat or salt|25.1|5.4|4.1|1.3|0.4
Buckwheat groats, uncooked|58|5|12.8|3.2|1.4
Buckwheat groats, cooked in water, no added salt|12.3|1.1|2.7|0.7|0.3
Bulgur, uncooked|66.1|7.8|10.3|2|1
Bulgur, soaked in water, no added fat or salt|34.4|4.1|5.4|1.3|0.5
Sago, dry, uncooked|85.5|0.4|0.2|0.2|0
Sago, cooked in water, no added salt|13.5|0.1|0|0|0
Cornmeal (polenta), uncooked|70|3|8.5|1.7|0.7
Cornmeal (polenta), boiled, no added fat or salt|22.3|1|2.7|0.5|0.2
Couscous, uncooked|73.6|4.2|13.8|0.2|3.4
Couscous, boiled, no added fat or salt|32.7|1.9|6.1|0.1|1.5
Millet, uncooked|67.4|3.7|10.2|3.5|0.6
Millet, boiled, no added fat or salt|22.1|1.2|3.3|1.1|0.2
Maize, grits, uncooked|73.3|2.7|8.1|1.3|0
Oat bran, unprocessed, uncooked|45|16.7|16.7|9.3|1.5
Oats, hulled, uncooked|55.5|8.6|12.5|9.8|1.2
Oats, rolled, uncooked|54.5|9.5|12.2|9.5|1
Oats, rolled, mixed with sugar or honey & other flavours, uncooked|69.1|7.5|8.3|7.5|24
Psyllium, uncooked|1|88.7|3|1.3|0
Quinoa, black, uncooked|50.6|16.1|13.1|6.6|4.3
Quinoa, red, uncooked|55|13.8|14.1|6.9|5.8
Quinoa, white, uncooked|61.8|10.5|12.2|6.3|4.2
Quinoa, uncooked|58.6|12|12.9|6.5|4.7
Quinoa, cooked in water, no added salt|16.3|3.4|3.6|1.8|1.3
Rye, grain, whole, uncooked|51.3|17.3|11.1|2.6|1.7
Rye, rolled, uncooked|50.8|17.3|10.4|2.6|1.4
Sorghum, grain, uncooked|65.8|8.6|8.2|3.6|1.2
Teff, uncooked|38.4|8|13.3|2.4|1.8
Triticale, grain, uncooked|58.3|14.4|9.6|2.4|1.8
Semolina, uncooked|61.6|3.2|10.7|1.2|0
Semolina, boiled, no added fat or salt|19.6|1|3.4|0.4|0
Spelt, uncooked|59.2|8.8|11.7|3|1.4
Spelt, boiled, no added fat or salt|24.5|3.6|4.8|1.2|0.6
Tapioca, pearl or seed style, uncooked|79.3|0|0.2|0.2|0
Tapioca, pearl or seed style, boiled, no added fat or salt|11.5|0|0|0|0
Wheat bran, unprocessed, uncooked|22.6|41.8|14.8|4.1|2.7
Wheat, whole, uncooked|58.3|11.4|11.2|2.4|1.3
Wheat germ|32.7|20.1|20.6|6.1|7.4
Rice, white, uncooked|74|0.8|7|0.9|0
Rice, white, boiled or rice cooker, no added salt|34.7|0.8|3.1|0.2|0.1
Rice, white, purchased as 'instant', microwaved|32.5|1|3.4|0.6|0
Rice, brown, uncooked|69.1|3.5|7.6|3.1|1.1
Rice, brown, boiled, no added salt|33.5|1.7|4.1|1.1|0.3
Rice, wild, uncooked|68.7|6.2|14.7|1.1|2.5
Rice, wild, boiled, no added salt|19.5|1.8|4|0.3|0.7
Rice paper wrapper, soaked in water|32.2|1|3.3|0.9|0.2
Rice, white, fried with bacon or ham, egg, prawns & vegetables|31|1.2|5.5|5.2|1
Flour, arrowroot|78.6|3.4|0.4|0.2|0
Flour, cornflour, from maize starch|84.1|0|0.4|0.6|0
Flour, cornflour, from maize & wheat starch|84.4|0.5|0.6|0.4|0
Flour, chickpea (besan)|38.5|20.8|19.7|5.5|2.2
Flour, gluten free, plain|80.5|1.7|3.4|1.1|0.3
Flour, rice|71.6|2.2|7.3|2|0.6
Flour, rye|54.9|17.3|11.4|2.3|1.4
Flour, soya (soy)|14.6|16|49.8|8.9|9.3
Flour, spelt|59.1|7.4|18.7|2.4|0.9
Flour, wheat, white, high protein or bread making flour|69.6|2.5|11.7|1.7|1.8
Flour, wheat, white, plain|73.2|2.2|10|1.3|1.9
Flour, wheat, white, plain, added vitamins B1, B2, B3, B6, E & folate & Fe, Mg & Zn (Jackaroo)|67.3|3.8|11.1|1.5|1.1
Flour, wheat, white, self-raising|73.2|2.2|10|1.3|1.9
Flour, wheat, white, self-raising, added vitamins B1, B2, B3, B6, E & folate & Fe, Mg & Zn (Jackaroo)|65.2|3.7|10.7|1.4|1.1
Flour, wheat, wholemeal, plain|61.6|8.8|11.6|1.6|2
Flour, wheat, wholemeal, self-raising|65.3|11.3|11.4|2.1|0.9
Gluten, from wheat (vital wheat gluten)|13.2|0.6|75.2|0|0
Starch, potato|74.5|5.9|0|0.5|0
Hamburger, white roll, beef patty, with cheese, onion, pickles & sauce, fast food chain|23.2|1.8|14.7|12.6|3.6
Hamburger, white roll, beef patty, with salad, independent takeaway outlet|18|2.6|12.1|9.2|1.3
Hot dog, bread roll, frankfurt & sauce filling|27.4|2.5|10.6|7.6|4
Pizza, cheese & tomato, commercial|28.7|2.8|16.2|11|2.7
Pizza, ham & pineapple, purchased frozen, baked|28.2|3.1|11.5|6|4.8
Pizza, ham & pineapple, takeaway style|23.1|3.1|12.8|8.3|2.7
Pizza, supreme, takeaway style|20.5|3|13|9.8|0.6
Pizza, supreme, fast food chain|27.6|2.5|12.9|9|2.4
Pizza, supreme, purchased frozen, baked|26.3|3.2|14|11.6|0.9
Pizza, vegetable, fast food chain|27.2|2.8|9.3|6|2.8
Noodle, rice stick, boiled, drained|26.1|0.7|2.5|0|0
Noodle, soba, dry|71.2|5.3|11.9|1.3|0
Noodle, soba, boiled, drained|21.5|1.6|3.6|0.4|0
Noodle, wheat, fresh, soaked, drained|26.5|1.7|6.3|1|0
Noodle, wheat, instant, flavoured, dry, uncooked|54|3.7|11|19.1|0.9
Noodle, wheat, instant, unflavoured, boiled, drained|17.6|1.2|3.8|6.2|0.3
Noodle, wheat, instant, unflavoured, boiled, undrained|6.6|0.4|1.4|2.3|0.1
Noodle, wheat, instant, unflavoured, dry, uncooked|54|3.7|11|19.1|0.9
Noodle, wheat, instant, flavoured, boiled, drained|17.6|1.2|3.8|6.2|0.3
Noodle, wheat, instant, flavoured, boiled, undrained|6.6|0.4|1.4|2.3|0.1
Noodle, wheat with egg, plain, boiled, no added fat|29.1|2.6|7.5|1.4|0.6
Pasta, gluten free, boiled from dry, no added salt|32.4|1.2|3.1|0.7|0
Pasta, legume based, boiled, no added salt|16.1|6.2|14.4|1.7|1.2
Pasta, white wheat flour, dry|68.5|3.2|12.7|1.4|0
Pasta, white wheat flour, boiled from dry, no added salt|26.2|2.2|5.3|0.4|0.2
Pasta, white wheat flour, fresh, uncooked|49.4|3.3|10.5|2.1|0.9
Pasta, white wheat flour, fresh, boiled, no added salt|27.1|2.7|5.9|1.3|1.1
Pasta, white wheat flour & egg, dry|74.4|2.2|10.6|1.6|1.9
Pasta, white wheat flour & egg, boiled from dry, no added salt|29.1|2.6|7.5|1.4|0.6
Pasta, white wheat flour & spinach, dry|73.1|4.4|11.9|1.3|2.7
Pasta, white wheat flour & spinach, boiled from dry, no added salt|27.3|1.4|3.8|0.4|0.2
Pasta, wholemeal wheat flour, dry|60.6|9.9|12.5|2.2|0
Pasta, wholemeal wheat flour, boiled from dry, no added salt|24.6|5.7|5.4|0.8|0
Pasta in cream based sauce, dry mix|69.7|3.6|13.1|3.1|9.3
Pasta in cream based sauce, prepared from dry mix|23.6|1.1|5.6|2.6|5.1
Lasagne, beef, commercial, purchased frozen, baked|11.8|2.1|6.6|6.6|1.6
Lasagne, beef, homemade|16.2|1.4|9.9|8|3
Macaroni & cheese, homemade, cooked unfilled pasta, homemade cheese sauce|21.5|1.7|6.4|5.5|1
Pasta dish, homemade, cooked unfilled pasta, homemade beef bolognese sauce & added vegetables|15.2|1.7|7.3|2.8|1.7
Spaghetti in tomato & cheese sauce, canned|10.7|0.8|2.5|0|4
Spaghetti in tomato & cheese sauce, canned, reduced salt|10.7|0.8|2.5|0|4
Croissant, plain|35.6|2.7|10|19.1|5.1
Pastry, filo, commercial, raw|55.8|2.5|10.2|3.8|1.3
Pastry, filo, commercial, baked|75.1|4.3|12.2|3.1|2.1
Pastry, puff, with butter, commercial, raw|32.9|3.4|5.5|19|1
Pastry, puff, with butter, commercial, baked|42.7|4.4|7.1|24.7|1.3
Pastry, puff, vegetable oil, commercial, raw|35|3.4|5.5|16.4|1
Pastry, puff, vegetable oil, commercial, baked|44.9|4.4|7|21|1.3
Pastry, shortcrust style, commercial, raw|41.3|6.4|6.4|21.4|1.2
Pastry, shortcrust style, commercial, baked|50.4|7.8|7.8|26.1|1.5
Pastry, filled with spinach & cheese, purchased frozen, baked|24.7|2.5|9.4|16.4|2.7
Pie, savoury, chicken & vegetable, commercial|25|2.1|8.2|11|1.2
Pie, savoury, meat, commercial, ready to eat|21.2|2.4|8.9|11.4|1.3
Pie, savoury, meat, purchased frozen, baked|19.3|2|8.6|11.3|0
Sausage roll, commercial, ready to eat|27.2|3|9.7|14.6|2.8
Sausage roll, purchased frozen, baked|27.2|3|9.7|12.7|2.8
Spring roll, meat & vegetable filling, commercial, deep fried|27.5|2.8|7.5|11.7|4.3
Pastry, danish, sweet, all types|35.6|3.7|7.5|15|16.4
Pie, sweet, apple, commercial|31.4|1.3|3|12.9|15.1
Tart, custard, commercial|30.2|1|6.3|10.7|11.4
Dressing, French or Italian, regular fat, commercial|11.7|0|0.1|19.6|11.4
Dressing, thousand island, regular fat, commercial|16.6|1.4|1|31.8|14.7
Mayonnaise, traditional (>65% fat), commercial|8.7|0.5|0.8|67.2|7.9
Mayonnaise, low fat, commercial|27.1|0|0.5|1.8|20.5
Gravy powder, dry mix|54.7|0.1|8.9|6.2|0.7
Gravy, prepared from dry powder with water|4.5|0|0.7|0.5|0
Mustard, cream style|6.2|4.1|5.8|3.1|3.6
Paste, Indian style curry, commercial|14.2|5.4|2.9|14.1|7.3
Paste, green curry, commercial|6.3|3|2.4|7.9|3.4
Paste, soybean|19.7|4.3|12.9|5.1|10.4
Paste, shrimp|0|0|21|1|0
Sauce, barbecue, commercial|42.2|0.9|0.8|0.1|38.4
Sauce, butter chicken, commercial|5.8|1.9|1.3|7.6|3.6
Sauce, cranberry, commercial|40|1.1|0|0|40
Sauce, fish, commercial|4.4|1.2|6.8|0.3|4.4
Sauce, hoi sin (hoisin), commercial|37.2|10.4|1.6|5.4|33.5
Sauce, oyster, commercial|21|0.3|2.8|1|15.6
Sauce, pasta or simmer, commercial, low fat|6.6|1.3|0.8|2.9|3.5
Sauce, pasta, basil pesto, commercial|7.4|1.5|5.3|37.1|2.1
Sauce, pasta, bolognese, homemade using beef mince & homemade tomato based sauce|3.8|1.4|8.8|4.5|3.2
Sauce, pasta, bolognese, homemade using beef mince & commercial tomato based sauce|3.9|1.1|10|6.2|3.3
Sauce, pasta, cheese or cream-based, commercial|5.3|0.1|1.4|6.8|2.7
Sauce, pasta, tomato-based, commercial, heated|7.5|1.8|1.6|1.5|6.2
Sauce, plum, commercial|56.5|0.6|0.1|0.8|48.2
Sauce, rogan josh, commercial|6.2|1.9|1.5|4.1|3.1
Sauce, salsa, tomato-based, commercial|6.3|1.6|1.3|0|4.9
Sauce, simmer for chicken, commercial|4.8|1.7|0.8|7.5|3.3
Sauce, simmer, curry flavoured, commercial|10.7|2.3|2.1|6.9|7.1
Sauce, soy, commercial|3.2|0|6.8|0|2.2
Sauce, soy, commercial, reduced salt|3.2|0|6.8|0|2.2
Sauce, tabasco, commercial|0.1|0.6|1.3|0.8|0.1
Sauce, tomato, commercial|24.1|1.3|1.3|0|23.7
Sauce, tomato, commercial, reduced salt|24.1|1.3|1.3|0|23.7
Sauce, tomato, reduced sugar & salt, commercial|11.3|1.3|1.3|0|10.9
Sauce, white, savoury, homemade|10.8|0.2|3.9|13.5|5.1
Sauce, Worcestershire, commercial|17.6|0|1.3|0.2|16.9
Vinegar (except balsamic vinegar)|0|0|0|0|0
Vinegar, balsamic|15|2.1|0.5|0|15
Peanut butter, smooth & crunchy, added sugar & salt|14.3|5.8|22.2|49.6|8.6
Peanut butter, smooth & crunchy, no added sugar or salt|9.4|6.5|24.3|54.3|5.2
Spread, yeast, marmite|16.6|11.8|13.3|0.9|11.8
Spread, yeast, vegemite|10.9|6.5|24.4|0.9|1.6
Cheese, blue vein|0.3|0|19.1|32.9|0.3
Cheese, bocconcini|0|0|16.6|20.2|0
Cheese, brie|0|0|18.6|31.8|0
Cheese, camembert|0|0|18.6|27.9|0
Cheese, cheddar, natural, regular fat|0.3|0|24.6|33.4|0.3
Cheese, cheddar, natural, reduced fat (~25%)|0|0|27.9|23.4|0
Cheese, cheddar, processed, regular fat|1.9|0|18.2|23.9|1.5
Cheese, cheddar, processed, reduced fat (~15%), added vitamin D|4.9|0|22.4|15.4|4.9
Cheese, cottage|1.8|0|17|5.7|1.8
Cheese, cream, plain|0.2|0|8.5|34.2|0.2
Cheese, edam|0|0|27.4|27.2|0
Cheese, fetta (feta)|0.4|0|16.6|20.6|0.4
Cheese, goat, firm|0.8|0|18.6|31.3|0.8
Cheese, goat, soft|0.9|0|21.2|21.7|0.9
Cheese, haloumi (halloumi)|1|0|20.5|24.6|1
Cheese, haloumi (halloumi), reduced salt|1.3|0|19|25.3|1.1
Cheese, mozzarella|0|0|22.2|22.1|0
Cheese, parmesan, dried, finely grated|0|0|40.6|33.3|0
Cheese, parmesan, fresh, regular fat|0|0|32|27|0
Cheese, ricotta|2.7|0|10.4|11|2.7
Cheese, hard, dairy alternative|19.1|2.6|0.9|23.3|0
Cream, thickened, regular fat (~35%)|2.4|0|2|36.5|2.4
Cream, sour, regular fat|2|0|1.9|36.3|2
Cream, sour, light (~18% fat)|4.5|0|3.6|19.7|4.5
Cream, imitation (non-dairy)|17.7|0|1|21.8|16.3
Ice cream, vanilla flavour, regular fat|20.5|0|3.7|11.1|18.4
Ice cream, vanilla flavour, premium or rich (~15% fat)|22.5|0|3.2|13.5|19.7
Ice confection, stick, milk-based, various flavours|19.8|0.2|2.9|5.3|19.8
Ice confection, stick, water-based, various flavours|17.1|0|0.2|0|17.1
Ice confection, stick or tub, fruit juice or fruit flavoured|18.7|0|0.2|0.1|18.7
Milk, cow, fluid, regular fat (~3.5%)|5.4|0|3.3|3.4|5.4
Milk, cow, fluid, prepared from dry powder, regular fat, standard dilution|6.1|0|3.6|3.6|6.1
Milk, cow, fluid, regular fat (~3.5%), added omega 3 polyunsaturates|5.1|0|3.1|3.2|5.1
Milk, cow, fluid, reduced fat (~1%)|4.8|0|3.3|1.2|4.8
Milk, cow, fluid, reduced fat (~1.5%), added omega 3 polyunsaturates|5.1|0|4.5|1.4|5.1
Milk, cow, fluid, skim (~0.15% fat)|5.3|0|3.6|0|5.3
Milk, cow, fluid, prepared from dry powder, skim, standard dilution|6|0|3.7|0.1|6
Milk, cow, fluid, lactose free, regular fat (~3.5%)|4.7|0|3.1|3.1|4.5
Milk, cow, fluid, lactose free, reduced fat (~1%)|4.9|0.1|3.3|1.1|4.7
Milk, cow, canned, evaporated, regular|10|0|7.5|8.2|10
Milk, cow, canned, evaporated, reduced fat (~2%)|10.6|0|7.8|2.1|10.6
Milk, cow, canned, sweetened, condensed, regular|55|0|8.3|9.2|55
Milk, cow, fluid, flavoured, chocolate, reduced fat|8.6|0|3.3|1.9|8.6
Milk, cow, powder, regular fat|43|0|25.3|25.6|43
Milk, cow, powder, regular fat, unfortified|43|0|25.3|25.2|43
Milk, cow, powder, regular fat, added vitamins A & D|43|0|25.3|27.3|43
Milk, cow, powder, skim|56|0|34.9|0.8|56
Milk, human/breast, mature, fluid|6.8|0|1.3|4.2|6.8
Custard, vanilla, regular fat|14.6|1.2|3.2|3|12.3
Custard powder, vanilla, dry mix|81.5|0.5|0.5|0.4|0.4
Custard, vanilla, prepared from dry mix|13.9|0|3.6|3.7|9.6
Yoghurt, natural, regular fat (~3%)|5|0.1|5.1|3.2|4.9
Yoghurt, natural, sheep's milk|2.2|0|5.9|6.5|2.2
Yoghurt, flavoured, high fat (~5%)|12.9|0.6|4.4|4.9|12.5
Yoghurt, apricot pieces or flavoured, regular fat (~3%)|11.1|0.1|4.2|3.2|11.1
Yoghurt, strawberry pieces or flavoured, regular fat (~3%)|11.6|0.1|4.9|3.1|11.6
Yoghurt, flavoured, low fat (~2%)|12.2|0.3|4.6|1.3|11.3
Yoghurt, vanilla flavoured (~2%)|14.5|0.2|5|1.9|13.5
Yoghurt, flavoured, low fat (0.2%), intense sweetened, increased protein|2.3|0.4|9.6|0.3|1.6
Yoghurt, vanilla flavoured, low fat (<0.5%)|13.9|0.2|5.9|0.5|12.7
Yoghurt, almond based, flavoured|9.2|1.8|2.6|4.5|5.5
Yoghurt, coconut based, flavoured|9.2|1.3|1.1|9|4.7
Yoghurt, soy based, berry flavoured, regular fat (~3%)|12.6|0.2|3.6|3.7|8.4
Yoghurt, soy based, vanilla flavoured, reduced fat (~1%)|12.6|0.2|3.6|0.8|8.4
Dairy or yoghurt dessert, chocolate, regular fat|14.6|1.4|2.6|4.5|11.6
Almond beverage, added sugar, unfortified|3|0.6|0.5|1.4|2.2
Almond beverage, no added sugar, unfortified|0|0.5|0.5|1.4|0
Almond beverage, added sugar & Ca|3|0.6|0.5|1.4|2.2
Almond beverage, no added sugar, added Ca|0|0.5|0.5|1.4|0
Almond beverage, added sugar & vitamins B1, B2, B12, C & Ca|3|0.6|0.5|1.4|2.2
Coconut beverage, unfortified|2.4|0|0.2|1.7|1
Coconut beverage, added Ca|2.4|0|0.2|1.7|1
Oat beverage, fluid, unfortified|10.2|0.3|1.3|0.8|3.1
Oat beverage, fluid, added Ca|10.2|0.3|1.3|0.8|3.1
Oat beverage, fluid, added calcium & vitamins|5.9|0.5|0.5|1.6|1.6
Rice beverage, unfortified|9|0|0.6|1|3.1
Rice beverage, fluid, added calcium|12.7|0|0.3|1|3.7
Soy beverage, regular fat (~3%), unfortified|4.6|0.6|3.7|2.7|2.6
Soy beverage, regular fat (~3%), added Ca|4.6|0.6|3.7|2.7|2.6
Soy beverage, regular fat (~3%), added Ca & vitamins A, B1, B2 & B12|4.6|0.6|4.4|2.7|2.6
Soy beverage, reduced fat (~1%), added Ca & vitamins A, B1, B2 & B12|5.7|0.6|3|0.9|1.7
Textured vegetable protein/soy granules|15.2|17.5|51.5|1.2|4.8
Tofu (soy bean curd), firm, as purchased|0|1|12.8|8.3|0
Meat alternative, mycoprotein/fungus base, commercial, as purchased|2.5|5.6|13.8|4.1|0
Meat alternative, mycoprotein/fungus base, schnitzels/fish alternative/patty/sausages, as purchased|16|4.2|12.3|8.7|1.8
Meat alternative, mycoprotein/fungus base, cooked|3.4|7.7|18.8|5.6|0
Meat alternative, protein (soy/wheat/pea) base, as purchased|3.2|4.4|19.4|13.4|0.8
Meat alternative, protein (soy/wheat/pea) base, cooked|4|5.4|23.9|16.5|1
Meat alternative, protein (soy/wheat/pea) base, added vitamins & minerals, as purchased|3.2|4.4|19.4|13.4|0.8
Meat alternative, legume and/or vegetable base, as purchased|21.3|5.5|7.5|5.9|4.9
Meat alternative, legume and/or vegetable base, cooked|26.3|6.8|9.2|7.3|6
Sausage, vegetarian style, unfortified, raw|7.4|1.5|19|9.4|1.9
Sausage, vegetarian style, added Fe, Zn and vitamin B12, raw|7.4|1.5|19|9.4|1.9
Sausage, vegetarian style, fried, no added fat|7.8|1.6|20|9.9|2
Soup, broth style, with meat, instant dry mix|65.7|4.3|4.8|3.3|5.7
Soup, broth style, with meat & noodles, instant dry mix|64.1|2.2|10.8|6.3|6
Soup, cream variety, instant dry mix|59.1|6.5|5.3|13.2|8.1
Soup, vegetable, instant dry mix|58.3|3.3|6.7|2.1|10.3
Soup, vegetable & noodle, instant dry mix|65.5|3.9|8.7|6.3|16
Soup, chicken & noodle, cup of soup, prepared from instant dry mix with water|3.4|0.3|0.7|0.2|0.4
Butter, plain, salted|0.6|0|1.1|82.2|0.6
Butter, plain, reduced salt|0.6|0|1.1|82.6|0.6
Butter, plain, no added salt|0.6|0|1.1|82.2|0.6
Ghee, clarified butter|0|0|0.3|99.9|0
Dairy blend, butter & edible oil spread (~80% fat), sodium 600 mg/100 g|1|0|0.5|78.1|0.6
Dairy blend, butter & edible oil spread (~80% fat), reduced salt (sodium 400 mg/100 g)|1|0|0.5|78.1|0.6
Margarine spread, monounsaturated (>65% fat)|0|0|0.5|67.3|0
Margarine spread, monounsaturated (>65% fat), reduced salt (sodium 360 mg/100 g)|0|0|1|68.9|0
Margarine spread, monounsaturated, reduced fat (<65%)|0|0|1|48|0
Margarine spread, monounsaturated, reduced fat (~55%) & salt (sodium 380 mg/100 g)|0|0|1|51.5|0
Margarine spread, monounsaturated (~70% fat), reduced salt (sodium 350 mg/100 g), added phytosterols|0|0|0.7|75.9|0
Margarine spread, polyunsaturated (~70% fat)|0|0|0|69|0
Margarine spread, polyunsaturated (~70% fat), reduced salt (sodium 280 mg/100 g)|0|0|1|70.8|0
Oil, almond|0|0|0|100|0
Oil, avocado|0|0|0|100|0
Oil, canola|0|0|0|100|0
Oil, coconut|0|0|0|99.7|0
Oil, copha|0|0|0.2|99.9|0
Oil, cottonseed|0|0|0|100|0
Oil, grapeseed|0|0|0|100|0
Oil, hemp|0|0|0|99.9|0
Oil, linseed or flaxseed|0|0|0|100|0
Oil, macadamia|0|0|0|100|0
Oil, maize|0|0|0|100|0
Oil, mustard seed|0|0|0|100|0
Oil, olive|0|0|0|99.9|0
Oil, palm|0|0|0|100|0
Oil, peanut|0|0|0|100|0
Oil, rice bran|0|0|0|100|0
Oil, safflower|0|0|0|100|0
Oil, sesame|0|0|0|100|0
Oil, soybean|0|0|0|100|0
Oil, sunflower|0|0|0|100|0
Oil, blend of polyunsaturated vegetable oils|0|0|0|100|0
Oil, blend of monounsaturated vegetable oils|0|0|0|100|0
Oil, vegetable|0|0|0|100|0
Dripping, beef|0|0|0.2|100|0
Fat, solid, vegetable oil based|0|0|0.2|100|0
Egg, chicken, whole, raw|0.3|0|12.6|8.5|0.3
Egg, chicken, whole, poached|0.3|0|13.1|8.8|0.3
Egg, chicken, whole, hard-boiled|0.7|0|12.4|9.4|0.3
Egg, chicken, whole, fried, no fat added|0.3|0|14.3|9.6|0.3
Egg, chicken, whole, omega-3 polyunsaturate enriched, raw|0.3|0|12.4|9.2|0.3
Egg, chicken, whole, omega-3 polyunsaturate enriched, boiled|0.3|0|12.7|9.4|0.3
Egg, chicken, yolk, raw|0.2|0|15.6|28.2|0.2
Egg, chicken, yolk, hard-boiled|0.2|0|16.1|31.7|0.2
Egg, chicken, white (albumen), raw|0.4|0|11.2|0|0.4
Egg, chicken, white (albumen), hard-boiled|0.4|0|11.6|0|0.4
Egg, chicken, whole, scrambled, with regular fat cow's milk, no fat added|2.1|0|10.8|7.7|2
Apple, bonza, unpeeled, raw|11.5|3.2|0.3|0.3|11.5
Apple, fuji, unpeeled, raw|12.1|2.4|0.3|0.3|12.1
Apple, golden delicious, unpeeled, raw|11.6|2.4|0.3|0|11.6
Apple, green skin, unpeeled, raw|11.2|2.5|0.3|0|11.1
Apple, granny-smith, unpeeled, raw|10.7|2.5|0.3|0|10.6
Apple, jonathan, unpeeled, raw|11.9|2.3|0.3|0|11.8
Apple, pink lady, unpeeled, raw|12.3|3|0.2|0|12.2
Apple, red delicious, unpeeled, raw|13.5|2.3|0.3|0|13
Apple, royal gala, unpeeled, raw|12.1|2.1|0.4|0|11.1
Apple, red skin, peeled, raw|13|1.8|0.2|0|12.7
Apple, red skin, unpeeled, raw|12.7|2.5|0.2|0|12.4
Apple, dried|59.8|8.5|1.2|0|59.4
Apricot, raw|7.3|1.8|0.2|0.4|7.2
Apricot, dried|43|8.3|4.2|0.4|42.3
Apricot, canned in pear juice|8.2|1.8|0.8|0|8.2
Apricot, canned in pear juice, drained|7.8|3.1|1|0|7.8
Apricot, canned in pear juice, juice only|8.7|0|0.5|0|8.7
Banana, cavendish, peeled, raw|19.9|2.2|1.4|0.2|13.4
Banana, lady finger or sugar, peeled, raw|26.9|3|1|0|16.9
Banana, frozen|20.5|2.3|1.4|0.2|13.7
Banana chip|50.7|7.7|2.3|33.6|35.3
Currant, fresh|7.6|4.3|1.4|0.3|7.4
Blackberry, raw|7.4|4.5|1.2|0.3|7.4
Blackberry, purchased frozen|7.4|4.5|1.2|0.3|7.4
Blueberry, raw|10.4|3|0.5|0|10
Blueberry, purchased frozen|10.7|3|0.5|0|10.3
Cherry, raw|15.2|1.4|1.1|0|15.2
Cherry, black, canned in syrup|16|0.9|0.7|0|15.8
Cherry, black, canned in syrup, drained|16|1.7|0.9|0.1|15.7
Cherry, black, canned in syrup, syrup only|16|0|0.5|0|16
Cherry, pitted, frozen|15.6|1.4|1.1|0|15.6
Cranberry, raw|4.3|3.6|0.5|0.1|4.3
Cranberry, dried, sweetened|77.5|5.3|0.2|1.1|72.6
Currant, dried|69|4.8|2.7|0.5|67.1
Cumquat (kumquat), raw|9.4|6.5|1.9|0.9|9.4
Custard apple, African pride, peeled, raw|16.3|3.2|1.4|0.5|13.1
Date, dried|67.2|9.7|2|0.2|65.9
Feijoa, raw|8|3|0.6|0.4|7.7
Fig, fresh, unpeeled, raw|8.1|3.3|1.4|0.3|8.1
Fig, dried|54.5|14.3|3.6|0.7|52.6
Goji berry, dried|49.1|11.2|11.9|3.7|47.7
Grape, black muscatel (muscat), raw|18.8|2.7|1.1|0.1|18.8
Grape, black sultana, raw|14|3.3|1.2|0.1|14
Grape, cornichon, raw|12.7|2.5|1.3|0.1|12.7
Grape, green, raw|15.8|1|0.8|0|15.4
Grape, red globe, raw|17.1|2.2|0.8|0.1|17.1
Grape, waltham cross, raw|14.4|3.5|0.8|0.1|14.4
Grapefruit, pink flesh, peeled, raw|7.7|1.4|0.8|0|7.7
Grapefruit, yellow flesh, peeled, raw|6.5|2.2|0.9|0.2|6.5
Guava, Hawaiian, raw|3.5|5.4|0.7|0.5|3.4
Jackfruit, peeled, raw|16.7|3|2.2|0.3|16.3
Kiwifruit, green (hayward), peeled, raw|9.1|3|1.2|0.7|8.8
Kiwifruit, gold, peeled, raw|11.1|1.8|1.2|0.5|10.9
Lemon, peeled, raw|1.8|2|0.6|0.1|1.8
Lemon peel, raw|0|10.6|1.5|0.3|0
Lemon, preserved|1.3|3.2|0.7|0.1|1.3
Lime, peeled, raw|1.2|3.6|0.8|0.1|1.2
Lime, native, fruit|0.4|3.6|0.1|2.7|0.4
Loquat, peeled, raw|4.9|1.7|1|0.2|4.9
Lychee, peeled, raw|16.8|1.2|0.8|0|16.8
Lychee, peeled, frozen|17.3|1.3|0.9|0|17.3
Mandarin, peeled, raw|9.8|1.6|0.8|0.2|9.8
Mandarin, canned in syrup|13.5|0.6|0.4|0.1|13.4
Mandarin, canned in syrup, drained|13.8|1.1|0.5|0.2|13.6
Mandarin, canned in syrup, syrup only|13.2|0|0.2|0|13.2
Mango, peeled, raw|12.6|1.3|0.4|0|12.1
Mango, peeled, frozen|12.9|1.3|0.4|0|12.4
Mango, pulped, canned|14|1.2|0.4|0|13.6
Melon, honey dew, white skin, peeled, raw|7.1|1|0.7|0.3|7.1
Melon, honey dew, yellow skin, peeled, raw|4.4|0.9|0.8|0.3|4.4
Melon, honey dew, peeled, raw|6.9|0.8|0.4|0|6.9
Melon, rockmelon, peeled, raw|4.8|1|0.6|0|4.8
Melon, watermelon, peeled, raw|7.1|0.4|0.6|0|6.8
Mulberry, raw|4.3|2.2|2.2|0.2|4.3
Nectarine, white, unpeeled, raw|6.7|2.8|0.8|0|6.7
Nectarine, yellow, unpeeled, raw|7.9|1.6|0.8|0|7.9
Orange, navel, peeled, raw|8.2|2.2|1|0|8.2
Orange, valencia, peeled, raw|8.2|2.4|1|0|8.2
Orange, peeled, raw|8.2|2.3|1|0|8.2
Passionfruit, raw|6.9|11|2.8|1.4|6.9
Passionfruit, pulp, frozen|7.1|11.2|2.9|1.5|7.1
Passionfruit, pulp, canned|20.4|5.5|1.4|0.7|20.4
Pawpaw (papaya), peeled, raw|8.4|2.1|0.4|0|8.4
Papaya, red, peeled, raw|11.8|2.2|0.5|0|11.8
Papaya, yellow, peeled, raw|6.4|1.9|0.5|0|6.4
Peach, white, unpeeled, raw|7.5|1.6|0.7|0|7.2
Peach, yellow, unpeeled, raw|7.7|1.6|0.7|0|7.4
Peach, canned in pear juice|8.3|1.3|0.6|0|8.3
Peach, canned in pear juice, drained|8.2|2|0.8|0.1|8.2
Peach, canned in pear juice, juice only|8.5|0.3|0.4|0|8.5
Pear, Packham's triumph, unpeeled, raw|12.5|3|0.4|0|12.4
Pear, William Bartlett, unpeeled, raw|9|3.7|0.3|0|9
Pear, green skin, unpeeled, raw|10.8|3|0.4|0|10.8
Pear, brown skin, unpeeled, raw|9.6|3.8|0.4|0|9.6
Pear, canned in pear juice|8.7|1.7|0.5|0|8.7
Pear, canned in pear juice, drained|8.6|2.8|0.6|0|8.6
Pear, canned in pear juice, juice only|8.8|0.2|0.2|0|8.8
Pear, nashi, unpeeled, raw|9.6|2.1|0.2|0|9.3
Persimmon, peeled, raw|16|2.7|0.6|0.1|16
Pineapple, peeled, raw|8.6|1.3|0.5|0|8.6
Pineapple, peeled, frozen|8.8|1.3|0.5|0|8.8
Pineapple, canned in pineapple juice|12|0.8|0.5|0.6|12
Pineapple, canned in juice, drained|10.9|1.1|0|0|10.9
Pineapple, canned in pineapple juice, juice only|10.6|0|0.3|0|10.6
Pineapple, canned in syrup|19.8|0.7|0.3|0.6|19.8
Pineapple, canned in syrup, drained|17.5|1.2|0|0|17.5
Pineapple, canned in syrup, syrup only|22|0|0.3|0|22
Plum, unpeeled, raw|9|2|0.6|0|9
Plum, dark, canned in syrup|21.3|1|0.4|0|21.1
Plum, dark, canned in syrup, drained|21.3|2|0.5|0.1|21
Plum, dark, canned in syrup, syrup only|21.3|0|0.3|0|21.2
Plum, davidson (native), flesh|14.3|2|1|0.2|3.9
Plum, salted|34.4|9.3|3.7|0|33.6
Pomegranate, peeled, raw|13.5|6.4|1.9|0.2|13.5
Prickly pear, peeled, raw|8.8|4.6|0.4|0.3|8.8
Prune (dried plum)|32.3|7.7|2.6|0.4|31
Quandong, fruit, flesh|8.1|4.2|2.5|0|8.1
Quince, peeled, raw|11|5.2|0.5|0.2|11
Raisin|74.4|2.2|2.1|0.9|74.1
Rambutan, raw|15.7|2.8|1|0.4|15.7
Raspberry, raw|6.9|4.8|1.1|0.3|6.7
Raspberry, purchased frozen|6.9|4.8|1.1|0.3|6.7
Raspberry, canned in syrup|17|2.5|0.6|0.1|16.8
Raspberry, canned in syrup, drained|16.2|6|1|0.2|15.9
Raspberry, canned in syrup, syrup only|17.5|0|0.2|0|17.5
Rhubarb, stalk, raw|1.7|2.2|1.5|0.2|1.7
Rhubarb, stewed, sugar sweetened, no added fat|13.3|2.1|1.4|0.2|13.3
Sultana|75.8|3.3|2.6|0.3|74
Strawberry, raw|5.6|2.1|0.7|0.2|5.5
Strawberry, purchased frozen|5.6|2.1|0.7|0.2|5.5
Strawberry, canned in syrup|18.8|1.5|0.4|0.2|18.7
Strawberry, canned in syrup, drained|18.7|3.5|0.9|0.1|18.5
Strawberry, canned in syrup, syrup only|18.9|0|0.1|0.2|18.8
Tamarillo, peeled, raw|3.4|4.7|2|0.1|3.4
Tamarind, paste, pure|57.4|5.1|2.8|0.6|38.8
Tangelo, peeled, raw|7.8|2|0.6|0.1|7.8
Tangerine or tangor, peeled, raw|8.1|1.5|1|0.2|8.1
Wax jambu, raw|4.5|1.9|0.7|0.2|4.5
Fruit salad, canned in fruit juice|9.8|1.4|0.7|0.1|9.8
Fruit salad, canned in fruit juice, drained|9.5|2.3|0.8|0.2|9.5
Fruit salad, canned in fruit juice, juice only|10.2|0.2|0.4|0|10.2
Mixed berry, frozen|9.6|3.3|0.6|0.1|9.2
Mixed dried fruit|73.6|3.3|2.2|0.7|71.2
Baked beans, canned in tomato sauce|13|4.8|4.8|0.3|5
Baked beans, canned in tomato sauce, reduced salt|13|4.8|4.8|0.3|5
Bean, broad, dried|37|18.6|24.3|2.1|2.7
Bean, broad, fresh, raw|2.2|7.1|6.9|0.5|0.7
Bean, broad, fresh, boiled, drained|2.4|7.6|7.4|0.5|0.8
Bean, butter, fresh, raw|2.3|2.3|2.3|0.2|1.8
Bean, butter, fresh, boiled, drained|2.5|2.5|2.5|0.2|1.9
Bean, haricot, dried|34.8|18.5|21.9|2.2|3.5
Bean, haricot, dried, boiled, drained|12.6|8.8|8.2|0.7|4.7
Bean, lima, dried|40.6|17.7|21.4|1.7|3.7
Bean, lima, dried, boiled, drained|10.2|5.3|6.4|0.3|0.6
Bean, mung, whole, dried, uncooked|41.9|13.7|24.5|2|2.1
Bean, red kidney, dried|35.6|21.5|22.5|1.8|3.4
Bean, red kidney, dried, boiled, drained|9.1|7.2|7.9|0.5|0.6
Bean, red kidney, fresh, raw|14.7|9.1|12.8|0.4|1.2
Bean, red kidney, fresh, boiled, drained|15.8|9.8|13.8|0.4|1.3
Bean, red kidney, canned, drained|14|6.5|6.6|0.6|2.4
Bean, soya (soy), dried|12.5|20.1|31.3|20.2|6.7
Bean, soya (soy), dried, boiled, drained|1.4|7.2|13.5|7.7|1.4
Chickpea, dried|38.5|20.8|19.7|5.5|2.2
Chickpea, dried, boiled, drained|14|7.6|7.2|2|0.8
Chickpea, canned, drained|13.3|5.7|6.3|2.1|0.6
Dip, hummus (hommus), commercial|9.4|3.9|6.9|19.5|0.8
Lentil, French, hulled, dry|38.8|15|28.3|2|1
Lentil, green, hulled, dry|40.9|14.6|25.3|1.7|1.5
Lentil, red, hulled, dry|45.7|13.7|23|1.9|2.7
Lentil, hulled, dry|41.8|14.4|25.5|1.9|1.7
Lentil, dried, boiled, drained|11.9|4.1|7.3|0.5|0.5
Lupin, dehulled, splits, uncooked|10|27.8|37.5|7.9|2.8
Lupin, dehulled, flakes, uncooked|10|27|36.9|7.7|3.6
Lupin, whole, uncooked|7.4|41.5|28.6|6.5|2.3
Pea, split, dried|50.5|10.1|23|2|2.5
Pea, split, dried, boiled, drained|9.1|8.3|6.6|0.4|2.9
Pea, green, whole, dried|40.9|16.8|22|2.1|2.4
Pea, yellow, whole, dried|43.6|17.7|23.3|2.3|2.6
Beef, all cuts, separable fat, raw|0|0|12.1|61.4|0
Beef, all cuts, separable fat, cooked, no added fat|0|0|15.3|55.6|0
Beef, casserole meat, boneless or bone-in, shin, lean, raw|0|0|21.9|0.6|0
Beef, casserole meat, boneless or bone-in, shin, lean, casseroled, no added fat|0|0.5|33.8|2.8|0
Beef, casserole meat, boneless or bone-in, shin, untrimmed, raw|0|0|21.3|4.2|0
Beef, casserole meat, boneless or bone-in, shin, untrimmed, casseroled, no added fat|0|0.4|30.6|11.8|0
Beef, casserole meat, boneless, chuck, lean, raw|0|0|23|3.4|0
Beef, casserole meat, boneless, chuck, lean, casseroled, no added fat|0|0|33|10.6|0
Beef, casserole meat, boneless, chuck, untrimmed, raw|0|0|21.7|10.4|0
Beef, casserole meat, boneless, chuck, untrimmed, casseroled, no added fat|0|0|31.2|15.1|0
Beef, diced, lean, raw|0|0|27.9|2.7|0
Beef, diced, lean, dry-fried, no added fat|0|0|32.1|2.9|0
Beef, diced, lean, casseroled, no added fat|0|0|36.5|3.5|0
Beef, diced, untrimmed, raw|0|0|27.6|3.9|0
Beef, diced, untrimmed, dry-fried, no added fat|0|0|31.8|3.7|0
Beef, diced, untrimmed, casseroled, no added fat|0|0|36.2|4.3|0
Beef, stir-fry strips, lean, raw|0|0|27.2|2|0
Beef, stir-fry strips, lean, dry-fried, no added fat|0|0|30.9|3.2|0
Beef, strips, lean, casseroled, no added fat|0|0|37.5|2.8|0
Beef, stir-fry strips, untrimmed, raw|0|0|27|2.7|0
Beef, stir-fry strips, untrimmed, dry-fried, no added fat|0|0|30.8|3.6|0
Beef, strips, untrimmed, casseroled, no added fat|0|0|37.3|3.2|0
Beef, mince, lower fat, raw|0|0|22.9|4.1|0
Beef, mince, lower fat, stir-fried, no added fat|0|0|32.3|8.1|0
Beef, mince, regular fat, raw|0|0|22.4|8.9|0
Beef, mince, regular fat, stir-fried, no added fat|0|0|29.5|16.4|0
Beef, mince, higher fat, raw|0|0|22.5|10.4|0
Beef, mince, higher fat, stir-fried, no added fat|0|0|27.6|13.5|0
Beef, rump steak, lean, raw|0|0|20.4|2.8|0
Beef, rump steak, lean, grilled, no added fat|0|0|32|4.5|0
Beef, rump steak, untrimmed, raw|0|0|20|5.6|0
Beef, rump steak, untrimmed, grilled, no added fat|0|0|29.9|10.8|0
Beef, sirloin steak, lean, raw|0|0|24.1|1.9|0
Beef, sirloin steak, lean, grilled, no added fat|0|0|30.5|3.8|0
Beef, sirloin steak, untrimmed, raw|0|0|23.4|5.4|0
Beef, sirloin steak, untrimmed, grilled, no added fat|0|0|27.3|14.7|0
Beef, steak, boneless, fillet or tenderloin, lean, raw|0|0|22.2|5.2|0
Beef, steak, boneless, fillet or tenderloin, lean, grilled, no added fat|0|0|31.9|5.5|0
Beef, steak, boneless, fillet or tenderloin, untrimmed, raw|0|0|21.4|9.3|0
Beef, steak, boneless, fillet or tenderloin, untrimmed, grilled, no added fat|0|0|30.8|8.8|0
Beef, steak, boneless or bone-in, blade, lean, raw|0|0|22.2|2.6|0
Beef, steak, boneless or bone-in, blade, lean, grilled, no added fat|0|0|31.9|2.9|0
Beef, steak, boneless or bone-in, blade, untrimmed, raw|0|0|21|9.5|0
Beef, steak, with bone, blade, untrimmed, grilled, no added fat|0|0|30.5|7.4|0
Beef, steak, boneless, round, lean, raw|0|0|21|1.7|0
Beef, steak, boneless, round, lean, grilled, no added fat|0|0|31.8|2|0
Beef, steak, boneless, round, untrimmed, raw|0|0|20.4|6|0
Beef, steak, boneless, round, untrimmed, grilled, no added fat|0|0|31|4.6|0
Beef, steak, boneless, scotch fillet, lean, raw|0|0|23.8|2.8|0
Beef, steak, boneless, scotch fillet, lean, grilled, no added fat|0|0|31.9|4.5|0
Beef, steak, boneless, scotch fillet, untrimmed, raw|0|0|22.4|10|0
Beef, steak, boneless, scotch fillet, untrimmed, grilled, no added fat|0|0|29.4|12.3|0
Beef, steak, with bone, T-bone, lean, raw|0|0|24.4|2|0
Beef, steak, with bone, T-bone, lean, grilled, no added fat|0|0|29.2|3.8|0
Beef, steak, with bone, T-bone, untrimmed, raw|0|0|21.3|16.9|0
Beef, steak, with bone, T-bone, untrimmed, grilled, no added fat|0|0|26.3|14.5|0
Beef, silverside roast, lean, raw|0|0|24.1|2.3|0
Beef, silverside roast, lean, roasted, no added fat|0|0|26.9|5.6|0
Beef, silverside roast, untrimmed, raw|0|0|22.8|8.7|0
Beef, silverside roast, untrimmed, roasted, no added fat|0|0|26|9.6|0
Beef, topside roast, lean, raw|0|0|22.3|3|0
Beef, topside roast, lean, roasted, no added fat|0|0|32.7|2.8|0
Beef, topside roast, untrimmed, raw|0|0|21.3|8.6|0
Beef, topside roast, untrimmed, roasted, no added fat|0|0|31.3|7.1|0
Meatball or rissole, beef mince, commercial, raw|2|1|15.5|16.3|0.3
Meatball or rissole, beef, commercial, cooked|2.7|1.4|20.9|22|0.4
Veal, all cuts, separable fat, raw|0|0|19.4|29|0
Veal, all cuts, separable fat, cooked|0|0|20.7|33|0
Veal, cutlet, with bone, lean, raw|0|0|24.6|1.1|0
Veal, cutlet, with bone, lean, grilled, no added fat|0|0|28.1|2|0
Veal, cutlet, with bone, untrimmed, raw|0|0|24.2|3|0
Veal, cutlet, with bone, untrimmed, grilled, no added fat|0|0|27.5|4.4|0
Veal, diced, lean, raw|0|0|23.1|1.5|0
Veal, diced, lean, stir-fried, no added fat|0|0|31.9|3|0
Veal, diced, untrimmed, raw|0|0|23|2|0
Veal, diced, untrimmed, stir-fried, no added fat|0|0|31.6|3.8|0
Veal, stir-fry strips, lean, raw|0|0|27.2|2|0
Veal, stir-fry strips, lean, stir-fried, no added fat|0|0|30|2.8|0
Veal, stir-fry strips, untrimmed, raw|0|0|27.1|2.5|0
Veal, stir-fry strips, untrimmed, stir-fried, no added fat|0|0|29.9|3.1|0
Veal, steak, boneless, leg, lean, raw|0|0|24.4|1.5|0
Veal, steak, boneless, leg, lean, grilled, no added fat|0|0|34.4|1.9|0
Veal, steak, boneless, leg, untrimmed, raw|0|0|24.3|1.8|0
Veal, steak, boneless, leg, untrimmed, grilled, no added fat|0|0|34.1|2.6|0
Lamb, all cuts, separable fat, raw|0|0|10.8|57.6|0
Lamb, all cuts, separable fat, cooked|0|0|16.2|53.4|0
Lamb, chop, with bone, chump, lean, raw|0|0|22.5|4.3|0
Lamb, chop, with bone, chump, lean, grilled, no added fat|0|0|34.1|10.2|0
Lamb, chop, with bone, chump, untrimmed, raw|0|0|19.6|17.5|0
Lamb, chop, with bone, chump, untrimmed, grilled, no added fat|0|0|30|20|0
Lamb, chop, with bone, forequarter, lean, raw|0|0|20.5|5.7|0
Lamb, chop, with bone, forequarter, lean, grilled, no added fat|0|0|28|11.4|0
Lamb, chop, with bone, forequarter, untrimmed, raw|0|0|17.4|22.4|0
Lamb, chop, with bone, forequarter, grilled, no added fat|0|0|25.6|19.9|0
Lamb, loin chop, lean, raw|0|0|28.6|5|0
Lamb, loin chop, lean, grilled, no added fat|0|0|29.8|9.1|0
Lamb, loin chop, untrimmed, raw|0|0|22|24.4|0
Lamb, loin chop, untrimmed, grilled, no added fat|0|0|26.8|18.9|0
Lamb, cutlet or frenched cutlet, with bone, lean, raw|0|0|20.9|6.2|0
Lamb, cutlet or frenched cutlet, with bone, lean, grilled, no added fat|0|0|30.5|8.6|0
Lamb, cutlet or frenched cutlet, with bone, untrimmed, raw|0|0|18.6|17.8|0
Lamb, cutlet or frenched cutlet, with bone, untrimmed, grilled, no added fat|0|0|27.2|18.9|0
Lamb, diced, lean, raw|0|0|21.3|5.2|0
Lamb, diced, lean, stir-fried, no added fat|0|0|31.3|6.5|0
Lamb, diced, lean, casseroled, no added fat|0|0|32.2|6.2|0
Lamb, diced, untrimmed, raw|0|0|21.1|6.2|0
Lamb, diced, untrimmed, stir-fried, no added fat|0|0|31|7.6|0
Lamb, diced, untrimmed, casseroled, no added fat|0|0|31.8|7.2|0
Lamb, stir-fry strips, lean, raw|0|0|21.9|4.2|0
Lamb, stir-fry strips, lean, stir-fried, no added fat|0|0|28.1|7.7|0
Lamb, stir-fry strips, lean, casseroled, no added fat|0|0|32.2|6.2|0
Lamb, stir-fry strips, untrimmed, raw|0|0|21.6|5.7|0
Lamb, stir-fry strips, untrimmed, stir-fried, no added fat|0|0|28|8.3|0
Lamb, stir-fry strips, untrimmed, casseroled, no added fat|0|0|32|6.8|0
Lamb, mince, raw|0|0|20.4|6.9|0
Lamb, mince, stir-fried, no added fat|0|0|24.4|8.5|0
Lamb, leg roast, lean, raw|0|0|21.1|3.2|0
Lamb, leg roast, lean, roasted, no added fat|0|0|30.6|6|0
Lamb, leg roast, untrimmed, raw|0|0|19.4|12.3|0
Lamb, leg roast, untrimmed, roasted, no added fat|0|0|28.5|12.8|0
Lamb, mini roast, lean, roasted, no added fat|0|0|27.8|5.6|0
Lamb, mini roast, untrimmed, roasted, no added fat|0|0|26.5|11|0
Lamb, roasting piece, shoulder, lean, raw|0|0|18.2|4.3|0
Lamb, roasting piece, shoulder, lean, roasted, no added fat|0|0|29.6|5.4|0
Lamb, roasting piece, shoulder, untrimmed, raw|0|0|16.5|16.6|0
Lamb, roasting piece, shoulder, untrimmed, roasted, no added fat|0|0|26.2|17.5|0
Lamb, eye of loin, lean, raw|0|0|20.8|3.9|0
Lamb, eye of loin, lean, microwaved, no added fat|0|0|26.8|5.6|0
Lamb, eye of loin, untrimmed, raw|0|0|20.6|5.3|0
Lamb, eye of loin, untrimmed, microwaved, no added fat|0|0|26.6|6.5|0
Lamb, fillet, lean, raw|0|0|19.8|4|0
Lamb, fillet, lean, grilled, no added fat|0|0|27.5|5.5|0
Lamb, fillet, untrimmed, raw|0|0|19.3|6.9|0
Lamb, fillet, untrimmed, grilled, no added fat|0|0|27.2|6.6|0
Lamb, steak, boneless, lean, raw|0|0|21.9|4.7|0
Lamb, steak, boneless, lean, grilled, no added fat|0|0|30.8|6.6|0
Lamb, steak, boneless, untrimmed, raw|0|0|20.4|11.8|0
Lamb, steak, boneless, untrimmed, grilled, no added fat|0|0|29.2|11.9|0
Lamb, with bone, shin, lean, raw|0|0|23.9|1.9|0
Lamb, with bone, shin, lean, casseroled, no added fat|0|0|34.6|4.5|0
Lamb, with bone, shin, untrimmed, raw|0|0|21.7|11.2|0
Lamb, with bone, shin, untrimmed, casseroled, no added fat|0|0|32|11.4|0
Mutton, all cuts, separable fat, raw|0|0|8.2|64.4|0
Mutton, all cuts, separable fat, cooked|0|0|10.7|67.9|0
Mutton, boneless dice or strips, shoulder, lean, raw|0|0|21.3|4.2|0
Mutton, boneless dice or strips, shoulder, lean, casseroled, no added fat|0|0|36.4|11.4|0
Mutton, boneless dice or strips, shoulder, semi-trimmed, casseroled, no added fat|0|0|35.2|13.9|0
Mutton, boneless dice or strips, shoulder, untrimmed, raw|0|0|20|10.2|0
Mutton, boneless dice or strips, shoulder, untrimmed, casseroled, no added fat|0|0|34.9|14.7|0
Mutton, roasting piece, with bone, leg, lean, raw|0|0|21.6|3.8|0
Mutton, roasting piece, with bone, leg, lean, roasted, no added fat|0|0|29.8|7.7|0
Mutton, roasting piece, with bone, leg, untrimmed, raw|0|0|19.6|12.9|0
Mutton, roasting piece, with bone, leg, untrimmed, roasted, no added fat|0|0|26.8|17.2|0
Buffalo, riverine, cube roll, raw|0|0|23.9|6.6|0
Buffalo, riverine, topside, raw|0|0|24.4|2.2|0
Buffalo, swamp, cube roll, raw|0|0|24.4|2.5|0
Buffalo, swamp, topside, raw|0|0|24.6|0.8|0
Camel, cube roll, raw|0|0|20.3|7.7|0
Camel, rump, raw|0|0|21.4|3|0
Camel, steak, as purchased, raw|0|0|20.8|4.3|0
Camel, steak, as purchased, fried, no added fat|0|0|32|6.7|0
Goat, all cuts, separable fat, raw|0|0|12.2|44.6|0
Goat, all cuts, separable fat, baked, no added fat|0|0|10|64.5|0
Goat, forequarter, lean, raw|0|0|21.6|1.8|0
Goat, forequarter, lean, baked, no added fat|0|0|30.8|4.2|0
Goat, leg, lean, raw|0|0|22.2|2.1|0
Goat, leg, lean, baked, no added fat|0|0|33.8|3.2|0
Goat, loin, lean, raw|0|0|22.2|1.4|0
Goat, loin, lean, baked, no added fat|0|0|29.2|3.1|0
Goat, meat, all cuts, lean, raw|0|0|22|1.8|0
Goat, meat, all cuts, lean, baked, no added fat|0|0|31.3|3.5|0
Goat, meat, all cuts, lean, casseroled, no added fat|0|0|43.6|3.5|0
Goat, meat, all cuts, untrimmed, raw|0|0|21.2|5.6|0
Goat, meat, all cuts, untrimmed, baked, no added fat|0|0|29.2|9.5|0
Goat, meat, all cuts, untrimmed, casseroled, no added fat|0|0|37.8|10|0
Rabbit, farmed, whole, raw|0|0|23.2|2.1|0
Rabbit, flesh, casseroled, no added fat|0.2|0|29.3|5.7|0.2
Venison, leg medallion, lean, raw|0|0|23.5|1.5|0
Venison, leg medallion, lean, baked, roasted, fried, stir-fried, grilled or BBQ'd, no added fat|0|0|28.7|1.9|0
Venison, mince, premium, raw|0|0|21|2.9|0
Venison, mince, premium, baked, roasted, fried, stir-fried, grilled or BBQ'd, no added fat|0|0|29.8|3.7|0
Pork, belly, rind removed, raw|0|0|18.6|26.2|0
Pork, belly, rind removed, baked, no added fat|0|0|27.4|30.8|0
Pork, crackling, roasted, salted|0|0|8.9|71.2|0
Pork, diced, untrimmed, raw|0|0|22.8|3.1|0
Pork, diced, untrimmed, fried, no added fat|0|0|31.3|3.1|0
Pork, strips, untrimmed, raw|0|0|22.4|2.3|0
Pork, strips, untrimmed, fried, no added fat|0|0|31.2|2.5|0
Pork, forequarter (chop, roast, neck), separable fat, raw|0|0|9.6|50.8|0
Pork, forequarter, separable fat, BBQ'd, no added fat|0|0|11.5|53.6|0
Pork, forequarter chop, lean, raw|0|0|19.5|3.9|0
Pork, forequarter chop, lean, BBQ'd, no added fat|0|0|26.3|6.3|0
Pork, forequarter chop, untrimmed, raw|0|0|17.5|13.2|0
Pork, forequarter chop, untrimmed, BBQ'd, no added fat|0|0|23.3|16|0
Pork, loin chop, separable fat, raw|0|0|7.9|69.2|0
Pork, loin chop, separable fat, BBQ'd, no added fat|0|0|8.8|70.7|0
Pork, loin chop, lean, raw|0|0|23.2|1.8|0
Pork, loin chop, lean, BBQ'd, no added fat|0|0|30.6|4.5|0
Pork, loin chop, untrimmed, raw|0|0|21.5|9.2|0
Pork, loin chop, untrimmed, BBQ'd, no added fat|0|0|28|12.2|0
Pork, loin cutlet, untrimmed, raw|0|0|21|13.1|0
Pork, loin cutlet, untrimmed, fried, no added fat|0|0|30.9|19.3|0
Pork, mince, untrimmed, raw|0|0|20.9|10.6|0
Pork, mince, as purchased, fried, no added fat|0|0|25.9|13.2|0
Pork, butterfly steak, lean, raw|0|0|24.1|1.6|0
Pork, butterfly steak, lean, grilled, no added fat|0|0|32.5|2.8|0
Pork, butterfly steak, untrimmed, raw|0|0|22.7|7|0
Pork, butterfly steak, untrimmed, grilled, no added fat|0|0|30.6|8.2|0
Pork, medallion or loin steak, separable fat, raw|0|0|6.3|68.5|0
Pork, medallion or loin steak, separable fat, fried, no added fat|0|0|14.9|55.5|0
Pork, medallion or loin steak, lean, raw|0|0|24.1|1.6|0
Pork, medallion or loin steak, lean, fried, no added fat|0|0|29.5|3.5|0
Pork, medallion or loin steak, untrimmed, raw|0|0|22.9|6.1|0
Pork, medallion or loin steak, untrimmed, fried, no added fat|0|0|33.2|8.8|0
Pork, leg steak (rump), separable fat, raw|0|0|8.6|66.4|0
Pork, leg steak (rump), separable fat, fried, no added fat|0|0|12|53.7|0
Pork, rump steak, lean, raw|0|0|25.3|2.1|0
Pork, rump steak, lean, fried, no added fat|0|0|28.6|3.8|0
Pork, rump steak, untrimmed, raw|0|0|24.8|4|0
Pork, rump steak, untrimmed, fried, no added fat|0|0|36.5|5.8|0
Pork, fillet, lean, raw|0|0|23.1|1.1|0
Pork, fillet, fully-trimmed, baked, no added fat|0|0|28.5|2.2|0
Pork, forequarter shoulder roast, lean, raw|0|0|19.6|3.1|0
Pork, forequarter shoulder roast, lean, BBQ'd, no added fat|0|0|24.8|6.4|0
Pork, forequarter shoulder roast, untrimmed, raw|0|0|17.6|12.5|0
Pork, forequarter shoulder roast, untrimmed, BBQ'd, no added fat|0|0|22.6|14|0
Pork, leg roast, untrimmed, raw|0|0|21.6|8.6|0
Pork, leg roast, untrimmed, baked, no added fat|0|0|31.2|10.7|0
Pork, loin roast, separable fat, raw|0|0|5.2|77.1|0
Pork, loin roast, separable fat, baked, no added fat|0|0|8.8|67|0
Pork, loin roast, lean, raw|0|0|23.3|2.2|0
Pork, loin roast, lean, baked, no added fat|0|0|31.9|8.6|0
Pork, loin roast, untrimmed, raw|0|0|19.9|16.4|0
Pork, loin roast, untrimmed, baked, no added fat|0|0|28.8|16.3|0
Pork, round mini roast, separable fat, raw|0|0|11.3|44.6|0
Pork, round mini roast, separable fat, baked, no added fat|0|0|16.3|45.5|0
Pork, round mini roast, lean, raw|0|0|23.7|1.1|0
Pork, round mini roast, lean, baked, no added fat|0|0|34.7|2.5|0
Pork, scotch roast, separable fat, raw|0|0|10.7|52.5|0
Pork, scotch roast, separable fat, baked, no added fat|0|0|12.2|50.5|0
Pork, scotch roast, lean, raw|0|0|20.6|7.6|0
Pork, scotch roast, lean, baked, no added fat|0|0|30.6|12|0
Pork, scotch roast, untrimmed, raw|0|0|18.9|15.4|0
Pork, scotch roast, untrimmed, baked, no added fat|0|0|29.6|14.1|0
Pork, spare ribs, untrimmed, raw|0|0|18.1|24|0
Pork, spare ribs, untrimmed, baked, no added fat|0|0|27.4|32.4|0
Pork, spare ribs, marinated, baked, roasted, fried, grilled or BBQ'd, fat not further defined|5.1|0.1|23.4|27|4.3
Chicken, separable fat, composite, raw|0|0|5.1|62.3|0
Chicken, separable fat, composite, baked or roasted, no added fat|0|0|9.8|46.6|0
Chicken, separable fat, composite, cooked, no added fat|0|0|6.2|75.5|0
Chicken, skin, composite, raw|0|0|12.7|35.1|0
Chicken, skin, composite, baked, no added fat|0|0|22.3|38.9|0
Chicken, skin, composite, casseroled, no added fat|0|0|18|38.8|0
Chicken, barbecued, with skin|0|1.1|27.5|8.9|0
Chicken, breast, lean flesh, raw|0|0|22.5|0.8|0
Chicken, breast, lean flesh, baked, no added fat|0|0|29|3.9|0
Chicken, breast, lean flesh, grilled, no added fat|0|0|29.8|2.5|0
Chicken, breast, lean flesh, fried, no added fat|0.7|0|34.5|1.8|0.7
Chicken, breast, lean flesh, casseroled, no added fat|0|0|27|3.7|0
Chicken, breast, lean flesh, skin & fat, raw|0|0|20.2|8.7|0
Chicken, breast, lean flesh, skin & fat, baked, no added fat|0|0|27.8|9.1|0
Chicken, drumstick, lean flesh, raw|0|0|18.5|4.8|0
Chicken, drumstick, lean flesh, baked, no added fat|0.4|0|29.9|8.5|0.4
Chicken, drumstick, lean flesh, casseroled, no added fat|0|0|28.6|6.6|0
""",
    """
Chicken, drumstick, lean flesh, skin & fat, raw|0|0|17.6|9.3|0
Chicken, drumstick, lean flesh, skin & fat, baked, no added fat|0.3|0|28.5|13.6|0.3
Chicken, drumstick, lean flesh, skin & fat, casseroled, no added fat|0|0|26.9|11.6|0
Chicken, mince, raw|0|0|21.4|1.1|0
Chicken, mince, fried, no added fat|0|0|28.1|1.4|0
Chicken, thigh, lean flesh, raw|0|0|19.1|3|0
Chicken, thigh, lean flesh, baked, no added fat|0|0|24.2|8.7|0
Chicken, thigh, lean flesh, casseroled, no added fat|0|0|26.3|9.8|0
Chicken, thigh, lean flesh, fried, no added fat|0|0|27.6|5.2|0
Chicken, thigh, lean flesh, skin & fat, raw|0|0|16|16.9|0
Chicken, thigh, lean flesh, skin & fat, baked, no added fat|0|0|23.1|16.1|0
Chicken, thigh, lean flesh, skin & fat, casseroled, no added fat|0|0|24.1|17.1|0
Chicken, wing, lean flesh, raw|0|0|18.7|4.1|0
Chicken, wing, lean flesh, baked, no added fat|0|0|30.9|7.7|0
Chicken, wing, lean flesh, casseroled, no added fat|0|0|27.9|8.8|0
Chicken, wing, lean flesh, skin & fat, raw|0|0|16.1|17.4|0
Chicken, wing, lean flesh, skin & fat, baked, no added fat|0|0|28.2|17.2|0
Chicken, wing, lean flesh, skin & fat, casseroled, no added fat|0|0|24.9|17.9|0
Chicken, nugget, purchased frozen, baked, no added fat|17.8|1.6|14.1|15.2|0.8
Chicken, nugget, purchased from independent takeaway & fast food chain, fried, undefined oil|16.8|1.1|13.1|15.8|0
Chicken, nugget, purchased from fast food chain, grilled|19.9|1.3|14.8|5.9|0.9
Chicken piece, flesh, skin & fat, coated, fast food outlet, fried|9.5|1|23.2|14.4|0
Chicken, schnitzel, baked without fat|13.1|1.7|16.5|9.6|2.1
Turkey, breast, lean flesh, raw|0|0|21.6|3.3|0
Turkey, breast, lean flesh, baked, no added fat|0|0|29.4|4|0
Turkey, breast, lean flesh, skin & fat, raw|0|0|20.4|8.5|0
Turkey, breast, lean flesh, skin & fat, baked, no added fat|0|0|27.7|9.2|0
Turkey, hindquarter, lean flesh, raw|0|0|18.4|5.3|0
Turkey, hindquarter, lean flesh, baked, no added fat|0|0|26.8|7|0
Turkey, hindquarter, lean flesh, skin & fat, raw|0|0|18.6|12.6|0
Turkey, hindquarter, lean flesh, skin & fat, baked, no added fat|0|0|25.6|12.9|0
Duck, skin & fat, raw|0|0|9.5|59.2|0
Duck, skin & fat, baked, no added fat|0|0|13.4|48.3|0
Duck, breast, lean flesh, raw|0|0|21.2|0.6|0
Duck, breast, lean, fried, no added fat|0|0|28|0.8|0
Duck, lean flesh, raw|0|0|17.8|5.5|0
Duck, lean flesh, baked, no added fat|0|0|28.3|0.8|0
Duck, lean flesh, skin & fat, raw|0|0|13|36.9|0
Duck, lean flesh, skin & fat, baked, no added fat|0|0|17.3|39.4|0
Quail, lean flesh & skin, raw|0|0|18.5|11|0
Quail, lean flesh & skin, baked, no added fat|0|0|27.7|9.6|0
Emu, fan fillet, raw|0|0|24.7|1.8|0
Emu, steak, as purchased, raw|0|0|23.4|2.5|0
Emu, steak, as purchased, fried, no added fat|0|0|30.8|3.3|0
Kangaroo, loin fillet, raw|0|0|21.4|0.9|0
Kangaroo, loin fillet, grilled, no added fat|0|0|30.1|1.2|0
Kangaroo, rump, raw|0|0|20.3|0.8|0
Kangaroo, rump, baked, no added fat|0|0|33.9|2.5|0
Kangaroo, steak, as purchased, raw|0.4|0|22.5|1|0.4
Kangaroo, steak, as purchased, fried, no added fat|0.6|0|34.6|1.5|0.6
Kangaroo, tail, raw|0|0|20|1.1|0
Kangaroo, tail, cooked, no added fat|0|0|24.4|1.3|0
Ostrich, fan fillet, raw|0|0|23.4|0.7|0
Ostrich, moon steak, raw|0|0|23.7|1.4|0
Pigeon (squab), whole, raw|0|0|16.2|25.5|0
Pigeon (squab), meat, cooked|0|0|22.3|35.2|0
Crocodile, back leg, raw|0|0|22|2.2|0
Crocodile, tail fillet, raw|0|0|22.5|1.6|0
Crocodile, steak, as purchased, raw|0|0|20.6|1.5|0
Crocodile, steak, as purchased, fried, no added fat|0|0|34.4|2.5|0
Chicken, liver, raw|0|0|16.9|4.8|0
Chicken, liver, fried, added butter|0|0|20.2|10.9|0
Lamb, liver, grilled, no added fat|2.6|0|21.5|10.2|0
Bacon, middle rasher, untrimmed, raw|0.2|0|15.4|28.2|0.2
Bacon, middle rasher, untrimmed, baked, roasted, fried, grilled or BBQ'd, no added fat|0.3|0|20.6|37.6|0.3
Bacon, middle rasher, separable lean, fried, no added fat|0.8|0|31.2|8.5|0.8
Bacon, middle rasher, separable fat, fried, no added fat|0.4|0|11.6|81.1|0.4
Bacon, shortcut, as purchased without rind, fried, no added fat|0.9|0|30.4|13.9|0.9
Ham, leg, lean|2|1.8|17|2.5|0.8
Prosciutto|0.3|0|30.8|18.8|0.3
Frankfurt, cooked|3.4|1.8|14.3|17|0.2
Sausage, beef, raw|2.9|0|14.5|18.7|0.4
Sausage, beef, fried, no added fat|4|0.4|16.4|18.4|0.4
Sausage, beef, grilled, no added fat|3.8|0.2|17.2|14.9|0.4
Sausage, chicken, plain, fried, no added fat|4.9|0.6|18.2|12|0.8
Sausage, chicken, flavoured, fried, no added fat|4.9|0.6|18.2|12|0.8
Sausage, lamb, plain, fried, no added fat|4.9|0.4|15.6|16.1|1.3
Sausage, lamb, flavoured, fried, no added fat|4.9|0.4|15.6|16.1|1.3
Sausage, pork, fried, no added fat|4.5|1.4|16.5|19.4|0.7
Sausage, chorizo, uncooked|1.2|0.5|20.1|22.1|0.8
Sausage, chorizo, fried, no added fat|1.3|0.5|21.2|23.3|0.8
Salami, danish|1.2|0|19.6|40.2|0.8
Salami, hungarian|0.9|0|21.6|37.4|0
Salami, mettwurst|0.8|0|21.9|37.5|0.5
Salami, milano|2.2|0|21.7|36.9|1
Salami, pepperoni|1.6|0|23.5|36.1|0.8
Salami, all varieties, raw|0.4|0.5|21.9|28.3|0
Chicken, processed luncheon meat, low or reduced fat|4.4|0.2|20|1.2|0.2
Beef, corned, canned|0.3|0.7|21.5|15.7|0.3
Braised steak & onions, canned|4.5|0.8|7.2|1.6|1
Pie, steak & kidney, canned|13.6|0.9|10|7.7|0.6
Sausages & vegetables, canned|7.8|0.9|4|2.1|2.2
Spam, canned|1.9|0|14.2|26.1|1.2
Steak & vegetables, canned|6.2|1.2|4.4|1.9|1.8
Coconut, fresh, mature fruit, flesh|3.3|8.1|3.8|32.8|2.9
Coconut, fresh, young or immature, flesh|7|0.5|1.6|5.6|4
Coconut, fresh, mature, water or juice|4.7|0|0.5|0.1|4.7
Coconut, fresh, young or immature, water or juice|6.6|0|0.2|0|6.6
Water, coconut, commercial|3.2|0|0.2|0|3.2
Coconut, grated & desiccated|7.3|15.4|6.9|65.4|6.6
Coconut, cream, regular fat|1.4|0|1.6|23.2|1.4
Coconut, milk, canned, regular fat|1.7|0|1.4|15.4|1.7
Coconut, milk, canned, reduced fat|0.9|0|0.6|7.1|0.9
Nut, almond, with skin, raw, unsalted|5.4|11|19.7|50.5|5.2
Nut, almond, with skin, roasted, unsalted|5.4|11|19.7|52.5|5.2
Nut, almond meal|5.4|11|19.7|50.5|5.2
Nut, brazil, raw or blanched, unsalted|2.4|8.5|14.4|68.5|2.1
Nut, cashew, raw, unsalted|22.9|5.9|17|49.2|5.5
Nut, cashew, roasted, unsalted|26.8|3.4|17|47.7|6.5
Nut, cashew, roasted, salted|26.7|3|17|48.5|6.4
Nut, chestnut, raw, unsalted|32.1|14.9|3.4|0.2|3.3
Nut, chestnut, roasted, unsalted|35.7|8|3.4|1.2|7.4
Nut, hazelnut, raw, unsalted|5.1|10.4|14.8|61.4|4.4
Nut, macadamia, raw, unsalted|4.6|7.9|6.5|76.7|4.6
Nut, peanut, with skin, raw, unsalted|8.9|8.2|24.7|47.1|5.1
Nut, peanut, with skin, roasted, with oil, salted|14.4|8.3|24.4|51.7|4.7
Nut, peanut, without skin, roasted, with oil, unsalted|14.1|6.2|25.1|52.8|4.4
Nut, peanut, without skin, roasted, with oil, salted|14.1|6.2|25.1|52.8|4.4
Nut, pecan, raw, unsalted|4.9|8.4|9.8|71.9|4.3
Nut, pine, raw, unsalted|4.5|5.1|13|70|3.4
Nut, pistachio, raw, unsalted|7.8|7.7|17|48.6|7.1
Nut, walnut, raw, unsalted|3|6.4|14.4|69.2|2.7
Seed, chia, dried|3.1|33.2|23.8|29.8|0.7
Seed, hemp, dried|2.5|4.2|29.8|50.6|2.3
Seed, linseed or flaxseed|2.8|32.9|21.6|32.6|1.5
Seed, poppy|8.6|19.5|18|41.6|3
Seed, pumpkin, hulled & dried|2.1|4.6|30.2|45|1.3
Seed, sunflower|3.1|13|24.7|48.3|2.1
Seed, sesame, unsalted|6.2|10.1|22.2|55.6|0.6
Wattle seed (acacia), ground|10.5|54.2|20.3|6.1|0
Flour, hemp|2.7|49.4|22.3|11.1|2.5
Tahini, sesame seed pulp|1|13.5|20.4|60.7|0.7
Barramundi, aquacultured, fillet, raw|0|0|19.2|5.3|0
Barramundi, aquacultured, fillet, grilled, no added fat|0|0|26.4|7.3|0
Barramundi, aquacultured, fillet, steamed with no added fat|0|0|22.9|6.3|0
Bassa (basa), fillet, raw|0|0|15.7|1.8|0
Bassa (basa), fillet, baked, no added fat|0|0|21.5|2.4|0
Bassa (basa), fillet, steamed, no added fat|0|0|18.7|2.1|0
Blue grenadier (hoki), fillet, raw|0|0|18|1.2|0
Blue grenadier (hoki), fillet, baked, no added fat|0|0|24.6|1.6|0
Blue grenadier (hoki), fillet, steamed, no added fat|0|0|21.4|1.4|0
Bream, fillet, raw|0|0|19.6|5.1|0
Bream, fillet, baked, no added fat|0|0|26.8|7|0
Bream, fillet, steamed, no added fat|0|0|22.4|5.4|0
Flathead, fillet, raw|0|0|21.1|1|0
Flathead, fillet, baked, no added fat|0|0|28.8|1.4|0
Flathead, fillet, steamed, no added fat|0|0|26.1|1.2|0
Gemfish, fillet, raw|0.1|0|14|2.6|0.1
Gemfish, fillet, baked, no added fat|0.1|0|19.2|3.6|0.1
Gemfish, fillet, steamed, no added fat|0.3|0|26.4|3.1|0.3
Kingfish, yellowtail, aquacultured, fillet, with skin, raw|0|0|22.9|12.7|0
Kingfish, yellowtail, aquacultured, fillet, with skin, grilled, no added fat|0|0|31.3|17.4|0
Kingfish, yellowtail, aquacultured, fillet, with skin, steamed, no added fat|0|0|27.2|15.1|0
Mackerel, raw|0|0|19.3|6.3|0
Mackerel, grilled, no added fat|0|0|26.4|8.6|0
Mackerel, steamed, no added fat|0|0|23|7.5|0
Milkfish, aquacultured, fillet, raw|0|0|23.4|10.7|0
Milkfish, aquacultured, fillet, grilled, no added fat|0|0|32|14.7|0
Milkfish, aquacultured, fillet, steamed, no added fat|0|0|27.8|12.8|0
Morwong, fillet, raw|0|0|19.4|5.8|0
Morwong, fillet, grilled, no added fat|0|0|26.5|7.9|0
Morwong, fillet, steamed, no added fat|0|0|23|3.7|0
Mullet, yelloweye, fillet, raw|0|0|19.2|6|0
Mullet, yelloweye, fillet, baked, no added fat|0|0|26.4|8.2|0
Mullet, yelloweye, fillet, steamed, no added fat|0|0|22|5|0
Mulloway, fillet, raw|0|0|20.6|2.3|0
Mulloway, fillet baked, no added fat|0|0|28.2|3.2|0
Mulloway, fillet, steamed, no added fat|0|0|23.2|3.9|0
Salmon, Atlantic, fillet, raw|0|0|20.5|16.7|0
Salmon, Atlantic, fillet, grilled, no added fat|0|0|22.9|18.6|0
Salmon, Atlantic, fillet, steamed, no added fat|0|0|24.1|19.6|0
Salmon, Pacific King, fillet, skinless, raw|0|0|18.1|22.8|0
Salmon, Pacific King, fillet, skinless, grilled, no added fat|0|0|20.2|25.4|0
Salmon, Pacific King, fillet, steamed, no added fat|0|0|21.3|26.8|0
Sardine, Australian, whole, raw|0|0|19.7|2.9|0
Sardine, Australian, whole, fried, no added fat|0|0|22|1.9|0
Sprat, blue, wild caught, flesh, skin & bones, raw|0|0|18.7|1.6|0
Sprat, blue, wild caught, flesh, skin & bones, fried, no added fat|0|0|22|1.9|0
Shark, fillet, without skin, raw|0|0|24.6|0.4|0
Shark, fillet, without skin, baked, no added fat|0|0|33.8|0.6|0
Shark, fillet, without skin, steamed, no added fat|0|0|27.7|0.5|0
Shark, battered, deep-fried, takeaway outlet|9.9|0.9|21.1|12.8|0.2
Silver perch, aquacultured, raw|0|0|19.5|16|0
Silver perch, aquacultured, baked, no added fat|0|0|26.7|22|0
Silver perch, aquacultured, steamed, no added fat|0|0|23.2|19.1|0
Snapper, fillet, raw|0|0|20.3|1.6|0
Snapper, fillet, baked, no added fat|0|0|27.8|2.2|0
Snapper, fillet, steamed, no added fat|0|0|27.8|2.2|0
Tilapia, fillet, raw|0|0|18.5|4.4|0
Tilapia, fillet, baked, no added fat|0|0|25.3|6|0
Tilapia, fillet, steamed, no added fat|0|0|22|5.2|0
Trout, ocean, aquacultured, fillet, without skin, raw|0|0|17.4|24.6|0
Trout, ocean, aquacultured, fillet, without skin, baked, no added fat|0|0|23.8|33.7|0
Trout, ocean, aquacultured, fillet, without skin, steamed, no added fat|0|0|20.7|29.3|0
Trout, rainbow, aquacultured, raw|0|0|19.6|8.4|0
Trout, rainbow, aquacultured, baked, no added fat|0|0|26.8|11.6|0
Trout, rainbow, aquacultured, steamed, no added fat|0|0|23.3|10|0
Tuna, yellowfin, fillet, raw|0|0|23.4|1|0
Tuna, yellowfin, fillet, baked, no added fat|0|0|32|1.4|0
Tuna, yellowfin, fillet, steamed, no added fat|0|0|27.8|1.2|0
Whiting, King George, fillet, raw|0|0|19.8|0.7|0
Whiting, King George, fillet, baked, no added fat|0|0|28.2|1|0
Whiting, King George, fillet, steamed, no added fat|0|0|23.3|1.1|0
Fish, eel, raw|0|0|18.4|11.7|0
Fish finger, crumbed, purchased frozen, raw|16.6|1.1|12.1|11.6|1.5
Fish finger, crumbed, purchased frozen, grilled, no added fat|18.9|1.2|13.8|13.2|1.7
Fish, white flesh, battered, packaged frozen, baked, no added fat|20.6|0.5|12.2|7|1.6
Fish, white flesh, crumbed, packaged frozen, baked, no added fat|18.8|1.5|13.6|10|0.8
Abalone, black lip, aquacultured, raw|1.6|0|16.2|0.8|0.4
Abalone, black lip, wild, raw|1.6|0|19.4|0.6|0.4
Abalone, green lip, aquacultured, raw|1.6|0|17.1|0.8|0.4
Abalone, green lip, wild, raw|1.6|0|18.7|0.5|0.4
Abalone, brown lip, wild, raw|1.6|0|17.8|0.6|0.4
Crab, flesh, purchased steamed or boiled|0|0|20.2|0.7|0
Lobster, southern rock, wild, flesh, raw|0|0|20.2|0.7|0
Lobster or crayfish, flesh, purchased steamed, poached or boiled, no added fat|0|0|22.5|0.8|0
Mussel, blue, steamed|4.7|0|16|2.3|0
Octopus, raw|0|0|14.9|1|0
Octopus, boiled, no added fat|0|0|16.6|1.2|0
Oyster, native, aquacultured, raw|0.6|0|7.3|1.7|0
Oyster, Pacific, aquacultured, raw|0.6|0|10.8|2.2|0
Oyster, Sydney rock, aquacultured, raw|0.6|0|10.6|2.8|0
Oyster, aquacultured, raw|0.6|0|10.1|2.3|0
Prawn, banana, wild, flesh, raw|0|0|13.2|0.6|0
Prawn, banana, aquacultured, purchased cooked|0|0|23.2|1|0
Prawn, brown tiger, wild, flesh, raw|0|0|23.4|0.9|0
Prawn, black tiger, aquacultured, purchased cooked|0|0|20.2|1|0
Prawn, endeavour, wild, flesh, raw|0|0|22.4|0.7|0
Prawn, school, wild, flesh, raw|0|0|17.8|1.1|0
Prawn, Western king, wild, flesh, raw|0|0|23.9|0.8|0
Prawn, flesh, raw (green)|0|0|20.7|0.8|0
Prawn, flesh, cooked from raw, no added fat|0|0|27.1|1.2|0
Scallop, raw|3.4|0|15.6|0.8|0.8
Scallop, fried, no added fat|4.2|0|19.5|1|1.1
Squid or calamari, raw|0|0|16.7|1.2|0
Squid or calamari, fried, no added fat|0|0|20.9|1.5|0
Anchovy, canned in oil, drained|0|0|25.4|8.9|0
Cod, smoked, raw|0|0|18.8|1|0
Cod or hake, smoked, steamed|0|0|20.1|1.5|0
Salmon, pink, canned in brine, drained|0|0|20.7|6.2|0
Salmon, red, canned in brine, drained|0|0|24.4|5.5|0
Salmon, flavoured, canned|5.5|0|15.5|5|4.1
Salmon, smoked, sliced|0|0|24.3|10.1|0
Tuna, unflavoured, canned in water, drained|0|0|26.1|2.6|0
Tuna, canned in brine, drained|0|0|26.1|2.6|0
Tuna, flavoured, canned in oil|3.3|0.3|18|5.8|2.5
Snack ball, date based|45|9.9|8.8|11.7|41
Bar, muesli, plain or with dried fruit|59.2|7|8.3|11.5|15.9
Bar, muesli, plain or with dried fruit, chocolate-coated|64.1|6.4|6.1|11.5|20.1
Bar, muesli, plain or with dried fruit, yoghurt-coated|59.5|6.4|6.6|14|23.1
Bar, muesli, with nuts|54.2|6.8|9.4|19.9|14
Bar, snack, oat based, baked|44.8|5.7|8|26.7|21.2
Bar, snack, fruit filled, baked|69.1|4.5|3.9|3|31.4
Bar, snack, with >55% nuts|31|6.8|18.8|34.6|15.9
Bar, snack, with >35% nuts, added protein|30|4.8|25|31.9|15
Extruded snack, cheese flavoured|53.5|3.2|8.8|25.8|2.5
Snack, grain based, extruded, flavoured|54.2|6.9|7.4|22.6|6.6
Noodle snack, wheat, flavoured, dry|60|1.7|10.4|23.6|1.8
Corn chips, plain, salted|56|5.2|7.5|25|0.6
Taco shell, from corn flour, plain|56.3|7.3|6.8|21.8|1.1
Popcorn, commercial, butter flavoured, salted|49.6|8.8|9.1|26.5|0.6
Potato crisps or chips, plain, salted|45.8|3.2|6|33.3|0.9
Potato crisps or chips, salt & vinegar flavoured|43.6|3.6|6.8|32.6|1.4
Potato crisps or chips, flavoured (other than salt & vinegar)|43.6|3.6|6.8|32.5|1.4
Potato crisps or chips, reformed, salted|55.1|2.8|5.7|28.1|1.9
Potato straws, French fries, plain|51.6|3.2|6.9|31|0.2
Vegetable crisps or chips, sweet potato & taro, flavoured|47.7|6.3|3|31.1|12.4
Bar, cherry & coconut centre, dark chocolate-coated|52.6|4.7|4.4|22.4|48.6
Bar, coconut cream centre, milk chocolate-coated|52.1|7.4|4.5|22.4|44.3
Bar, honeycomb centre, milk chocolate-coated|70|0.3|2.6|16.2|64.2
Bar, nougat & caramel centre, milk chocolate-coated|68.9|1.4|3.4|15.9|58.8
Bar, wafer & cream layers, chocolate-coated|59.7|1.9|6.2|27.2|51.6
Chocolate, compound, cooking|57|4.6|4|30.3|55.3
Chocolate, dark, no added sugar|4.1|9.9|6.9|38.3|0.5
Chocolate, dark, high cocoa solids|62.6|1.2|3.9|30.3|52
Chocolate, milk|56|2.3|7.6|30.6|54.6
Chocolate, milk, fondant or caramel filled|61.4|2|6|24.7|53.6
Chocolate, milk, with dried fruit & nuts|55.4|2.9|7.8|28|53.4
Chocolate, milk, with nuts|40.7|4.7|9.9|39.5|39.6
Chocolate, white|54.6|0.2|7.1|34.5|54.6
Confectionery, almond, chocolate-coated|48.4|3.6|9.4|33.5|47.2
Confectionery, chocolate centre, sugar-coated|76.7|1.8|4.8|18.8|64.9
Confectionery, dried fruit & nuts, chocolate-coated|49.6|3.8|9.5|29.6|47
Confectionery, peanut, chocolate-coated|41.9|4.3|13.3|37.7|37.6
Confectionery, sultana, chocolate-coated|61.9|2.6|6.1|21.5|60.4
Liquorice, chocolate-coated|66|3.2|6.3|9.7|44.8
Capers, pickled, canned, drained|1.7|3.2|2.4|0.9|0.4
Chutney or relish, commercial|43.1|2.2|0.5|0.3|41.5
Jam, berry|67.3|1.6|0.3|0|67.2
Jam, plum|64.4|0.9|0.2|0|64.3
Jam, stone fruit|64.4|0.9|0.2|0|64.3
Marmalade, orange|65.5|0.8|0.1|0|65.4
Spread, hazelnut & chocolate flavoured|57.8|3.7|6.8|30.3|57.2
Sugar, brown|96.8|0|0.2|0|96.8
Sugar, raw|99.8|0|0|0|99.8
Sugar, white, granulated or lump|100|0|0|0|100
Sugar, white, icing|100|0|0|0|100
Sugar, white, icing mixture|99.6|0|0|0|95.6
Glucose, liquid or syrup|87.5|0|0|0|33
Honey|72.3|0|0.3|0|72.3
Syrup, golden|74.9|0|0.2|0|74.9
Syrup, maple, pure|67|0|0|0.1|60.4
Syrup, malt|75|0|6.2|0|49
Syrup, rice malt|82.6|0|0.3|0|54.5
Treacle or molasses|69.2|0.4|0.6|0|69.2
Jelly crystals, sugar sweetened, all flavours|90.7|0|7.1|0|90.7
Jelly, sugar sweetened, all flavours, prepared|13.2|0|1|0|13.2
Caramels, soft & hard|74.5|1.5|2.5|11.4|54.6
Liquorice, allsorts|88.1|1.4|2.3|0.3|76.2
Liquorice, black|70.3|3.5|5.7|0.8|40.6
Lolly, hard varieties|98.4|0|0|0|93.2
Lolly, jelly varieties|75.1|0|3.9|0|45.3
Lolly, mint flavoured, sugar sweetened|93.3|0|0.1|2|74.3
Lolly, with fruit juice|82.2|0|2.2|0|57.4
Meringue, all flavours, commercial|90.1|0|2.6|1.4|89.4
Basil, green, fresh, raw|0.2|3.3|2.6|0.5|0
Chives, raw|2.6|4.7|1.5|0.3|2.6
Coriander, fresh, raw|1.9|3.2|3.1|0.6|1.2
Dill, fresh, raw|2.5|3.3|3.4|1.2|1.2
Marjoram, fresh, raw|0.6|14.1|3.3|5.9|0.4
Mint, fresh, raw|4.4|7.2|3.1|1.1|0.9
Oregano, fresh, raw|0.6|14.1|3.3|5.9|0.4
Parsley, continental, fresh, raw|1.1|5|3.9|0.7|1
Parsley, curly, fresh, raw|0.4|5.8|1.9|0.2|0.4
Rosemary, fresh, raw|0.6|14.1|3.3|5.9|0.4
Sage, fresh, raw|0.6|14.1|3.3|5.9|0.4
Thyme, fresh, raw|1.1|14|5.6|1.7|1
Artichoke, globe, raw|1.3|8.1|2.8|0.2|0.9
Artichoke, globe, boiled, drained|1.5|9.5|3.3|0.2|1
Artichoke, jerusalem, peeled, raw|10.5|3|2.1|0.1|2.7
Artichoke, jerusalem, peeled, boiled, drained|11.3|3.2|2.2|0.1|2.9
Artichoke heart, canned in brine, drained|1.2|4.7|1.9|0.3|1.1
Asparagus, green, raw|1.4|2.2|2.5|0.1|1.4
Asparagus, green, boiled, drained|1.6|2.5|2.9|0.1|1.6
Asparagus, green, fresh or frozen, baked, roasted, stir-fried or fried, grilled or BBQ'd, no added fat|1.7|2.6|3|0.1|1.7
Asparagus, canned in brine, drained|1.5|3.9|1.9|0.1|1.5
Avocado, hass, raw|2.2|5.9|1.6|14.6|0
Avocado, shepard, raw|2.2|4.3|1.6|14.6|0
Bamboo shoot, canned in water, heated, drained|1.3|2.8|0.8|0|0.8
Bean, green, fresh, raw|3.3|2.8|1.8|0|2.6
Bean, green, fresh, boiled, drained|3.5|3|1.9|0|2.8
Bean, green, frozen, boiled, drained|2.8|3.8|1.4|0.2|1.3
Bean, green, fresh, cooked, no added fat|3.9|3.2|2.1|0|3
Beetroot, fresh, purple, peeled, raw|9.4|3.4|2|0.6|9.3
Beetroot, fresh, purple, peeled, baked, no added fat|11.4|4.2|2.4|0.7|11.3
Beetroot, fresh, purple, peeled, boiled, drained|10|3.7|2.1|0.6|10
Beetroot, canned in brine, drained|8.7|2|1.2|0|8.7
Bok choy, raw|0.6|2.4|2.5|0.2|0.6
Bok choy, fried, no added fat|0.8|3.5|3.6|0.3|0.8
Bok choy, steamed|0.6|2.6|2.6|0.2|0.6
Broccoli, fresh, raw|1.2|3.5|4|0.4|1.2
Broccoli, fresh, microwaved|1.3|2.9|3.2|0.4|1.3
Broccoli, fresh, boiled, drained|1.2|2.5|2.9|0.3|1.1
Broccoli, fresh, baked, no added fat|1.4|4.3|4.9|0.4|1.4
Broccolini, fresh, raw|2|2.5|3.2|0.4|1.3
Broccolini, fresh, boiled, drained|2.1|2.6|3.3|0.4|1.4
Brussels sprout, fresh, raw|4.2|4.6|3.8|0.3|3.4
Brussels sprout, fresh, boiled, drained|4.3|4.8|4|0.3|3.6
Brussels sprout, fresh, baked, no added fat|4.6|5.1|4.2|0.4|3.8
Cabbage, Chinese, raw|0.9|1.1|1.1|0|0.9
Cabbage, Chinese, boiled, drained|0.9|1.1|1.1|0|0.9
Cabbage, Chinese flowering, raw|0.9|2.8|1.3|0.3|0.8
Cabbage, Chinese flowering, boiled, drained|0.9|2.9|1.4|0.3|0.8
Cabbage, mustard, raw|0.7|1.8|2.3|0.3|0.7
Cabbage, mustard, boiled, drained|0.7|1.9|2.4|0.3|0.7
Cabbage, red, raw|3.2|3.1|2.2|0.3|3.2
Cabbage, red, boiled, drained|3.3|3.2|2.3|0.3|3.3
Cabbage, savoy, raw|2.3|2.3|1.7|0.1|2.3
Cabbage, savoy, boiled, drained|2.4|2.4|1.8|0.1|2.4
Cabbage, white, raw|3.8|2.2|1.2|0|3.8
Cabbage, white, boiled, drained|3.9|2.3|1.2|0|3.9
Capsicum, green, fresh, raw|2.5|1.5|0.9|0|2.5
Capsicum, green, fresh, fried, no added fat|2.8|1.7|1|0|2.8
Capsicum, red, fresh, raw|4.4|1.1|1.1|0.4|4.4
Capsicum, red, fresh, fried, no added fat|4.9|1.2|1.2|0.4|4.9
Capsicum, yellow, fresh, raw|4.3|1.5|1.1|0.4|4.3
Carrot, baby, peeled, fresh, raw|5.5|3.3|0.7|0.1|5.5
Carrot, baby, peeled, fresh, boiled, drained|5.9|3.5|0.8|0.1|5.9
Carrot, baby, baked, no added fat|6|3.6|0.8|0.1|6
Carrot, mature, peeled, fresh, raw|6.6|3.2|0.6|0|6.2
Carrot, mature, peeled, fresh, baked, no added fat|8|3.9|0.7|0|7.6
Carrot, mature, peeled, fresh, boiled, drained|5.1|3.3|1.1|0.3|4.6
Cassava, peeled, fresh, raw|30.4|4.6|1.1|0.2|1.2
Cassava, peeled, fresh, boiled, drained|32.7|5|1.2|0.3|1.3
Cassava, white flesh, peeled, fresh, raw|34.1|4.6|0.6|0.3|1.2
Cassava, white flesh, peeled, fresh, boiled, drained|36.7|4.9|0.6|0.3|1.3
Cassava, yellow flesh, peeled, fresh, raw|26.7|4.7|1.6|0.2|1.2
Cassava, yellow flesh, peeled, fresh, boiled, drained|28.7|5|1.7|0.2|1.3
Cauliflower, fresh, raw|3.3|1.2|1.9|0.2|2.8
Cauliflower, fresh, boiled, drained|3.4|1.2|2|0.2|2.9
Cauliflower, fresh, baked, no added fat|4|1.5|2.3|0.2|3.4
Celeriac, peeled, raw|2.8|5.1|1.6|0.2|2.8
Celeriac, peeled, boiled, drained|3|5.5|1.7|0.2|3
Celery, fresh, raw|1.8|1.6|0.6|0.1|1.8
Celery, fresh, fried, no added fat|2.1|1.9|0.8|0.1|2.1
Chicory, raw|0.8|1.8|1.8|0.2|0.7
Chicory, boiled, drained|0.9|2.1|2.1|0.2|0.8
Chilli (chili), green, raw|1|12.9|2.8|0.3|1
Chilli (chili), green, fried, no added fat|1.1|14.3|3.1|0.3|1.1
Chilli (chili), red, raw|4.8|7.2|1.4|0.7|4.8
Chilli (chili), red, fried, no added fat|5.3|8|1.6|0.8|5.3
Choko, peeled, fresh, raw|3.6|1.4|0.5|0.2|3.1
Choko, peeled, fresh, boiled, drained|4.3|1.7|0.6|0.2|3.7
Corn, fresh on cob, raw|12.5|4.2|3.6|1.8|5.4
Corn, fresh on cob, boiled, drained|13|4.4|3.8|1.9|5.6
Sweetcorn, fresh or frozen on cob, baked, roasted, fried, stir-fried, grilled or BBQ'd, no added fat|13.9|4.7|4|2|6
Corn, kernels, purchased frozen, raw|16.8|2.5|3|0.9|3
Corn, kernels, purchased frozen, boiled, drained|19.9|3.5|3.1|0.9|2.6
Corn, kernels, canned in brine, drained|16.8|2.7|2.9|1|3.5
Cucumber, common, peeled, raw|2.1|0.6|0.4|0.1|2.1
Cucumber, common, unpeeled, raw|1.2|1.4|0.8|0.1|1.2
Cucumber, Lebanese, peeled, raw|1.9|0.7|0.7|0|1.8
Cucumber, Lebanese, unpeeled, raw|1.8|0.6|0.7|0|1.7
Cucumber, telegraph, unpeeled, raw|1.4|0.9|1.2|0.1|1.4
Eggplant, unpeeled, fresh, raw|2.6|2.6|1|0.2|2.6
Eggplant, unpeeled, fresh, roasted, no added fat|4.2|4.5|1.4|0.2|4.2
Endive, fresh, raw|0.4|2.2|1.5|0.2|0.3
Fennel, fresh, raw|3.3|1.8|1|0.1|3.3
Fennel, fresh, boiled, drained|3.9|2.1|1.2|0.1|3.9
Garlic, peeled, fresh, raw|10.2|16.9|6.1|2.8|1.5
Garlic, peeled, fresh, fried, no added fat|16.4|27.2|9.8|4.5|2.4
Gherkin, pickled, drained, commercial|24.4|0.9|0.4|0.3|23.6
Ginger, peeled, fresh, raw|4.8|2.8|0.8|0.4|1.7
Ginger, peeled, fresh, fried, no added fat|5.8|3.4|1|0.5|2.1
Kale, raw|0.3|5.2|3.3|0|0.3
Kale, fried, no added fat|0.4|7.5|4.7|0|0.4
Kohlrabi, peeled, fresh, raw|4.2|3.3|3.7|0.1|4.2
Kohlrabi, peeled, fresh, boiled, drained|4.4|3.4|3.8|0.1|4.4
Leek, fresh, raw|4.1|3.1|1.6|0.2|4.1
Leek, fresh, fried, no added fat|4.9|3.8|2|0.2|4.9
Leek, fresh, boiled, drained|4.6|3.5|1.8|0.2|4.6
Lettuce, cos, raw|1.8|1.8|1.2|0.2|1.8
Lettuce, iceberg, raw|2.1|1.1|1|0|2.1
Lettuce, mignonette, raw|1.1|1.4|1.3|0.3|1.1
Melon, bitter, fresh, raw|0.2|3.3|0.9|0|0.1
Melon, hairy, fresh, raw|2|2.1|0.7|0|1.7
Mushroom, common, fresh, raw|0.8|1.4|2.2|0.8|0.8
Mushroom, common, fresh, fried, no added fat|3.1|2.7|5|1.4|3.1
Mushroom, common, vitamin D enhanced, fresh, raw|0|1.4|2.2|0.4|0
Mushroom, common, vitamin D enhanced, fresh, fried, no added fat|0|2.5|4|0.7|0
Okra, raw|1.4|3.7|3.1|0.2|1.4
Okra, boiled, drained|1.5|4|3.3|0.2|1.5
Olive, green or black, drained|0|3.2|2.2|21.7|0
Onion, mature, brown skinned, peeled, raw|5.8|2.3|1.3|0|5.8
Onion, mature, brown skinned, peeled, fresh, fried, no added fat|9.4|3.8|2.1|0|9.4
Onion, mature, red skinned, raw|5.5|1.8|0.8|0|5.5
Onion, mature, red skinned, peeled, fried, no added fat|8.9|2.8|1.2|0|8.9
Onion, mature, white skinned, peeled, fresh, raw|4.7|2.1|1.7|0.1|4.7
Onion, spring, fresh, raw|4.6|1.9|1.7|0.2|4.5
Onion, spring, fresh, fried, no added fat|7.4|3.1|2.7|0.3|7.2
Parsnip, peeled, fresh, raw|10.1|4.1|1.6|0.4|5.6
Parsnip, peeled, fresh, baked, no added fat|12.3|5|2|0.5|6.8
Parsnip, peeled, fresh, boiled, drained|10.9|4.4|1.8|0.4|6
Pea, green, fresh, raw|7.7|6.6|6.5|0.5|3.4
Pea, green, fresh, boiled, drained|6.4|5.2|4.8|0.4|2.7
Pea, green, frozen, boiled, drained|6.4|6.1|5.2|0.3|2.5
Potato, coliban, peeled, raw|11.5|1.1|2|0|1
Potato, coliban, peeled, baked, no added fat|17.4|1.7|3|0|1.5
Potato, coliban, peeled, boiled, drained|11.6|2|1|0|0
Potato, desiree, peeled, raw|10.7|1.7|2.3|0|1.7
Potato, desiree, peeled, baked, no added fat|16.2|2.6|3.5|0|2.6
Potato, desiree, peeled, boiled, drained|10.9|1.7|2.3|0|1.7
Potato, new, peeled, raw|12.8|1.1|2.3|0.1|0.6
Potato, new, peeled, baked, no added fat|19.4|1.7|3.5|0.2|0.8
Potato, new, peeled, boiled, drained|12.8|2.3|2.5|0.1|0.4
Potato, pale skin, peeled, raw|11.5|1.1|2|0|1
Potato, pale skin, peeled, boiled, drained|12.1|2|1.5|0.1|0.3
Potato, pale skin, peeled, baked, no added fat|17.4|1.7|3|0|1.5
Potato, pontiac, peeled, raw|12.6|2.1|2.4|0.1|0.7
Potato, pontiac, peeled, baked, no added fat|19.1|3.2|3.6|0.2|1.1
Potato, pontiac, peeled, boiled, drained|12.8|2.1|2.4|0.1|0.7
Potato, red skin, peeled, raw|10.7|1.7|2.3|0|1.7
Potato, red skin, peeled, baked, no added fat|16.2|2.6|3.5|0|2.6
Potato, red skin, peeled, boiled, drained|14.2|2.4|2|0|0.2
Potato, sebago, peeled, raw|13.8|2.2|2.5|0.2|0.4
Potato, sebago, peeled, baked, no added fat|20.8|3.3|3.8|0.2|0.7
Potato, sebago, peeled, boiled, drained|13.1|2.5|2.5|0.4|1
Potato, chips, regular, fast food outlet, deep fried, blended oil, salted|28.2|3|3.8|10.1|0
Potato, chips, regular, fast food outlet, deep fried, monounsaturated oil, salted|28.2|3|3.8|12.2|0
Potato, chips, regular, independent takeaway outlet, deep fried, blended oil, salted|28.2|3|3.8|11.6|0
Potato, chips, regular, purchased frozen, baked, no added fat|24.1|3.8|3.8|5.9|0
Potato, fries, fast food outlet, deep fried, monounsaturated oil, salted|35.7|3.1|4.8|16.4|0.2
Potato, fries, independent takeaway outlet, deep fried, blended oil, salted|35.7|3.1|4.8|17.4|0.2
Potato, wedges, regular, purchased frozen, baked or roasted, no added fat|24.1|3.8|3.8|5.9|0
Pumpkin, butternut, peeled, fresh, raw|6.9|1.8|2.1|0.6|5.2
Pumpkin, butternut, peeled, fresh, baked, no added fat|8.1|2.1|2.5|0.7|6.1
Pumpkin, butternut, peeled, fresh, boiled, drained|7.2|1.9|2.2|0.6|5.4
Pumpkin, golden nugget, peeled, fresh, raw|4|1.1|2.2|0.3|2.6
Pumpkin, golden nugget, peeled, fresh, baked, no added fat|4.7|1.3|2.6|0.4|3
Pumpkin, golden nugget, peeled, fresh, boiled, drained|4.2|1.1|2.3|0.3|2.7
Pumpkin, jarrahdale, peeled, fresh, raw|4.3|1.6|0.7|0.1|4.3
Pumpkin, jarrahdale, peeled, fresh, baked, no added fat|5|1.9|0.8|0.1|5
Pumpkin, jarrahdale, peeled, fresh, boiled, drained|4.5|1.7|0.7|0.1|4.5
Pumpkin, kent/jap, peeled, fresh, raw|4.3|1.8|1|0.3|3.8
Pumpkin, kent/jap, peeled, fresh, boiled, drained, no added fat|4.5|1.9|1|0.3|4
Pumpkin, kent/jap, peeled, fresh, baked, no added fat|5|2.1|1.2|0.4|4.5
Pumpkin, Queensland blue, peeled, fresh, raw|8.4|2.7|2|0.3|4.9
Pumpkin, Queensland blue, peeled, fresh, baked, no added fat|9.9|3.2|2.4|0.4|5.8
Pumpkin, Queensland blue, peeled, fresh, boiled, drained|8.8|2.8|2.1|0.3|5.1
Pumpkin, peeled, fresh, raw|6.7|1.9|1.2|0|6
Pumpkin, peeled, fresh, baked, no added fat|7.9|2.2|1.5|0|7
Pumpkin, peeled, fresh, boiled, drained|7|2|1.3|0|6.2
Radish, red skinned, unpeeled, raw|1.9|1.1|0.8|0.2|1.9
Radish, white skinned, peeled, raw|2.9|1.8|0.7|0.3|2.9
Rocket, fresh, raw|0.3|2.4|3.3|0.3|0.3
Seaweed, nori, dried|2.3|34.3|46.7|4.3|0.3
Seaweed, boiled, drained|0.4|6.2|8.4|0.8|0
Shallot, peeled, fresh, raw|3.1|2.9|1.5|0.1|3.1
Shallot, peeled, fresh, fried, no added fat|5|4.7|2.4|0.2|5
Silverbeet, fresh, raw|1.4|2.3|2.1|0.2|1.4
Silverbeet, fresh, boiled, drained|1.7|2.7|2.5|0.3|1.7
Snow pea, fresh, raw|5.6|2.6|3|0|4
Snow pea, fresh, fried, no added fat|7|3.2|3.8|0|5
Snow pea, fresh, boiled, drained|6|2.8|3.2|0|4.3
Spinach, baby, fresh, raw|0|2.5|2.8|0|0
Spinach, Mature English, fresh, raw|0.6|2.5|2.4|0.3|0.6
Spinach, Mature English, fresh, boiled, drained|0.7|2.9|2.8|0.4|0.7
Spinach, water, fresh, raw|1|2.8|2.9|0.5|0.5
Sprout, alfalfa, fresh, raw|0.5|2.2|3.2|0.3|0.5
Sprout, bean, fresh, raw|1.6|1.3|3.1|0.1|1
Squash, button, fresh, raw|3.2|0.5|2.7|0.2|2.9
Squash, button, fresh, boiled, drained|3.8|0.6|3.2|0.2|3.4
Squash, scallopini, fresh, raw|2.1|0.5|2.2|0.2|2
Squash, scallopini, fresh, boiled, drained|2.5|0.6|2.6|0.2|2.4
Swede, peeled, fresh, raw|3.7|2.2|0.9|0|3.4
Swede, peeled, fresh, boiled, drained|4|2.4|1|0|3.6
Sweet potato, orange flesh, peeled, fresh, raw|13.2|2.6|1.4|0.2|5.8
Sweet potato, orange flesh, unpeeled, raw|13.2|2.7|1.4|0.2|5.8
Sweet potato, orange flesh, peeled, fresh, baked, no added fat|16.1|3.2|1.8|0.2|7.1
Sweet potato, orange flesh, peeled, fresh, boiled, drained|14.2|2.8|1.6|0.2|6.2
Sweet potato, purple flesh, peeled, raw|25.5|2.7|1.4|0.2|4.2
Sweet potato, purple flesh, peeled, baked, no added fat|31.1|3.3|1.8|0.2|5.1
Sweet potato, white flesh, peeled or unpeeled, fresh or frozen, baked, roasted, fried, stir-fried, grilled or BBQ'd, no added fat|29.5|3.9|1.8|0.2|5.6
Sweet potato, white flesh, peeled, raw|24.2|3.2|1.4|0.2|4.6
Taro, peeled, fresh, raw|23.4|3.5|1.9|0.2|1.1
Taro, peeled, fresh, boiled, drained|25.2|3.8|2|0.2|1.2
Tomato, cherry, raw|3.5|1.7|0.9|0|3.5
Tomato, common, raw|3|1.1|0.6|0|2.5
Tomato, paste, with added salt|10.4|3.5|3.1|0.3|9.9
Tomato, paste, no added salt|10.1|3.6|3.4|0.5|9.6
Tomato, puree, commercial|3.8|1.5|1.2|0.1|3.8
Tomato, sundried|35.2|14.7|11.2|4.6|33.9
Tomato, whole, canned in tomato juice, undrained|3|1|0.9|0|3
Tomato, whole, canned in tomato juice, drained|3|1.3|0.8|0.3|3
Tomato, whole, canned in tomato juice, no added salt, undrained|3|1|0.9|0|3
Turnip, white, peeled, fresh, raw|3.4|1.7|1.3|0|3.2
Turnip, white, peeled, fresh, boiled, no added fat|3.6|1.8|1.4|0|3.4
Vine leaf, grape, canned|1.8|9.9|4.3|2|1.8
Water chestnut, peeled, canned, drained|9.3|2.7|0.6|0.9|5.1
Watercress, raw|0.8|3.8|2.9|0.4|0.7
Yam, wild harvested, cooked|20.9|3.5|3.2|0.3|0.5
Zucchini, green skin, fresh, unpeeled, raw|2.2|1|1|0.2|2.2
Zucchini, green skin, fresh, unpeeled, fried, no added fat|3.1|1.5|1.5|0.2|3.1
Zucchini, green skin, fresh, unpeeled, boiled, drained|2.6|1.2|1.2|0.2|2.6
Zucchini, golden, fresh, unpeeled, raw|1.1|1|2.2|0.3|1.1
Zucchini, golden, fresh, unpeeled, boiled, drained|1.3|1.2|2.6|0.4|1.3
Mixed leafy greens, pre-packaged, all types|0|2|2.2|0|0
Mixed vegetables, purchased frozen, carrot, corn & pea/bean, boiled, drained|5.9|5.9|1.8|0.3|2.7
Stock, liquid, all flavours (except fish), prepared from commercial powder or cube|0.4|0|0.2|0.2|0.1|l
Vanilla, artificial or imitation|14.4|0|0|0|14.4|l
Beer, high alcohol (5% v/v & above)|2.1|0|0.5|0|0.2|l
Beer, full strength (alcohol 4-4.9% v/v)|2.3|0|0.4|0|0|l
Beer, full strength (alcohol 4-4.9% v/v), carbohydrate modified|1|0|0.3|0|0|l
Beer, mid-strength (alcohol 3-3.9% v/v)|2.2|0|0.3|0|0|l
Beer, light (alcohol 1-2.9% v/v)|2.2|0|0.2|0|0|l
Alcoholic beverage, spirit, ~30% v/v, all (Brandy, Gin, Rum, Vodka and Whisky), cooked|0.3|0|0|0|0.3|l
Alcoholic beverage, spirit, ~40% v/v, all (Brandy, Gin, Rum, Vodka and Whisky)|0.1|0|0|0|0.1|l
Cider, apple (alcohol ~4-5% v/v)|5.6|0|0|0|5.6|l
Mirin|26.6|0|0.3|0|26.6|l
Wine, red|0.2|0|0.2|0|0.2|l
Wine, red, cabernet sauvignon|0.2|0|0.3|0|0.2|l
Wine, red, merlot|0.4|0|0.3|0|0.4|l
Wine, red, pinot noir|0.2|0|0.2|0|0.2|l
Wine, red, shiraz|0.1|0|0.2|0|0.1|l
Wine, red, sparkling|2|0|0.2|0|2|l
Wine, red, cooked|0.2|0|0.3|0|0.2|l
Wine, rose|0.7|0|0.2|0|0.7|l
Wine, white, sauvignon blanc|0.1|0|0.2|0|0.1|l
Wine, white, semillon|0.2|0|0.2|0|0.2|l
Wine, white, chardonnay|0.3|0|0.3|0|0.3|l
Wine, white, riesling|0.4|0|0.2|0|0.4|l
Wine, white|0.2|0|0.3|0|0.2|l
Wine, white, cooked|0.3|0|0.2|0|0.3|l
Wine, white, sparkling|1|0|0.2|0|1|l
Wine, white, sweet dessert style|15.3|0|0.4|0|15.3|l
Wine, fortified, port|12.9|0|0.2|0|12.9|l
Wine, fortified, sherry, dry style (~1% sugars)|1.2|0|0.1|0|1.2|l
Wine, fortified, sherry, sweet style (~11% sugars)|11.4|0|0.2|0|11.4|l
Beverage, chocolate flavour, from Milo powder, with regular fat cow's milk|10.5|0.6|4.2|4.1|9|l
Beverage, chocolate flavour, from drinking chocolate, with regular fat cow's milk|10.6|0.1|3.6|3.6|10.4|l
Protein powder, whey based, protein >70%, unfortified, prepared with water|0.3|0.3|14.3|0.4|0.2|l
Coffee, prepared from coffee mix with sugar & whitener, no added milk|2.8|0.2|0.6|0.6|1.9|l
Coffee, black, from instant coffee powder|0.2|0.1|0.2|0|0|l
Coffee, black, from instant coffee powder, decaffeinated|0.2|0.1|0.2|0|0|l
Coffee, espresso, from ground coffee beans|0|0|1.7|0.3|0|l
Coffee, long black, from ground coffee beans|0|0|0.3|0.1|0|l
Coffee, flat white/latte/cappuccino, from ground coffee beans, with regular fat cow's milk|3.5|0|2.9|2.6|3.5|l
Cordial base, 25% citrus fruit juice, regular|45.5|0|0|0|45.5|l
Cordial base, 40% citrus fruit juice, regular|49|0|0.2|0|49|l
Cordial base, blackcurrant juice, regular|54.9|0|0.1|0|54.9|l
Cordial base, fruit juice/flavours, intense sweetened or diet|7.2|0|0|0|7.2|l
Cordial, 25% citrus fruit juice, regular, recommended dilution|9.2|0|0|0|9.2|l
Cordial, 40% citrus fruit juice, regular, recommended dilution|9.9|0|0|0|9.9|l
Cordial, blackcurrant juice, regular, recommended dilution|9.8|0|0|0|9.8|l
Cordial, fruit juice/flavours, recommended dilution|6.9|0|0|0|6.9|l
Cordial, fruit juice/flavours, intense sweetened or diet, recommended dilution|0.7|0|0|0|0.7|l
Juice, apple, commercial, added vitamin C|7.4|0.2|0.2|0|7.4|l
Juice, apple & blackcurrant, commercial|9.7|0|0.2|0|9.7|l
Juice, lemon|1.9|2.1|0.7|0.1|1.9|l
Juice, lime|1.3|3.8|0.8|0.1|1.3|l
Juice, orange, commercial|7.1|0.4|0.8|0|7.1|l
Juice, orange & mango, commercial|8|0.2|0.6|0|8|l
Juice, vegetable blend, commercial|2.8|0.6|0.9|0|2.2|l
Fruit drink, apple juice|8.5|0|0.1|0|8.1|l
Fruit drink, blackcurrant, commercial|10.2|0|0.2|0|10.2|l
Fruit drink, cranberry|12.7|0.1|0|0|11.8|l
Fruit drink, orange juice|10|0.1|0.2|0|10|l
Soft drink, cola flavour|12.8|0|0|0.3|12.8|l
Soft drink, cola flavour, decaffeinated|12.8|0|0|0.3|12.8|l
Soft drink, cola flavour, intense sweetened or diet|0|0|0|0|0|l
Soft drink, cola flavour, intense sweetened or diet, decaffeinated|0|0|0|0|0|l
Soft drink, dry ginger ale or ginger beer, regular|10.2|0|0|0|10.2|l
Soft drink, fruit flavours|9.2|0|0|0|9.2|l
Soft drink, tonic water|9.1|0|0|0|9.1|l
Soft drink, fruit flavours, intense sweetened or diet|0|0|0|0|0|l
Soft drink, tonic water, intense sweetened or diet|0|0|0|0|0|l
Soft drink, energy drink, Red Bull|11.3|0|0.1|0|11.3|l
Soft drink, energy drink, V|11.3|0|0.1|0|11.3|l
Kombucha, flavoured, added juice or intense sweeteners|0.8|0.1|0|0|0.8|l
Tea, regular, black, brewed from leaf or teabags, without milk|0|0|0.1|0|0|l
Tea, black, brewed from leaf or teabags, decaffeinated|0|0|0.1|0|0|l
Tea, green, plain, without milk|0|0|0.2|0|0|l
Mineral water, natural, unflavoured|0|0|0|0|0|l
Mineral water, citrus flavoured|3.8|0|0|0|3.8|l
Water, bottled, still|0|0|0|0|0|l
Water, soda|0|0|0|0|0|l
Water, tap|0|0|0|0|0|l
Breakfast cereal, beverage, non-chocolate flavours, added vitamins & minerals (Up & Go)|12.8|0.8|3.2|1.5|7.2|l
Dressing, French or Italian, regular fat, commercial|12|0|0.1|20.2|11.7|l
Dressing, thousand island, regular fat, commercial|17.9|1.5|1.1|34.3|15.9|l
Mayonnaise, traditional (>65% fat), commercial|8.6|0.5|0.8|66.5|7.8|l
Mayonnaise, low fat, commercial|29.3|0|0.5|1.9|22.1|l
Gravy, prepared from dry powder with water|4.6|0|0.7|0.5|0|l
Mustard, cream style|6.5|4.3|6.1|3.2|3.8|l
Sauce, barbecue, commercial|47.7|1|0.9|0.1|43.4|l
Sauce, butter chicken, commercial|6.6|2.1|1.5|8.5|4.1|l
Sauce, cranberry, commercial|46.8|1.3|0|0|46.8|l
Sauce, fish, commercial|5.4|1.5|8.3|0.4|5.4|l
Sauce, hoi sin (hoisin), commercial|40.2|11.2|1.8|5.8|36.2|l
Sauce, oyster, commercial|25.6|0.4|3.4|1.2|19|l
Sauce, pasta or simmer, commercial, low fat|7.4|1.5|0.9|3.3|4|l
Sauce, pasta, basil pesto, commercial|8.5|1.7|6.1|42.7|2.4|l
Sauce, pasta, cheese or cream-based, commercial|5.6|0.1|1.4|7.2|2.9|l
Sauce, pasta, tomato-based, commercial, heated|8.2|2|1.8|1.6|6.9|l
Sauce, plum, commercial|72.9|0.8|0.2|1|62.2|l
Sauce, rogan josh, commercial|7|2.1|1.7|4.6|3.5|l
Sauce, salsa, tomato-based, commercial|6.9|1.8|1.4|0|5.4|l
Sauce, simmer for chicken, commercial|5.4|1.9|0.9|8.5|3.7|l
Sauce, simmer, curry flavoured, commercial|12.1|2.6|2.4|7.8|8|l
Sauce, soy, commercial|3.4|0|7.2|0|2.3|l
Sauce, soy, commercial, reduced salt|3.4|0|7.2|0|2.3|l
Sauce, tabasco, commercial|0.1|0.6|1.3|0.7|0.1|l
Sauce, tomato, commercial|25.1|1.4|1.4|0|24.6|l
Sauce, tomato, commercial, reduced salt|25.1|1.4|1.4|0|24.6|l
Sauce, tomato, reduced sugar & salt, commercial|11.7|1.4|1.4|0|11.3|l
Sauce, white, savoury, homemade|11.5|0.2|4.2|14.3|5.4|l
Sauce, Worcestershire, commercial|21.5|0|1.5|0.2|20.6|l
Vinegar (except balsamic vinegar)|0|0|0|0|0|l
Vinegar, balsamic|15.2|2.1|0.5|0|15.2|l
Cream, thickened, regular fat (~35%)|2.5|0|2.1|37.6|2.5|l
Cream, sour, regular fat|2|0|1.9|35.6|2|l
Cream, sour, light (~18% fat)|4.6|0|3.7|20.1|4.6|l
Cream, imitation (non-dairy)|17.9|0|1|22|16.5|l
Ice cream, vanilla flavour, regular fat|11.3|0|2|6.1|10.1|l
Ice cream, vanilla flavour, premium or rich (~15% fat)|14.2|0|2|8.5|12.4|l
Milk, cow, fluid, regular fat (~3.5%)|5.5|0|3.4|3.5|5.5|l
Milk, cow, fluid, prepared from dry powder, regular fat, standard dilution|6.3|0|3.7|3.7|6.3|l
Milk, cow, fluid, regular fat (~3.5%), added omega 3 polyunsaturates|5.2|0|3.2|3.3|5.2|l
Milk, cow, fluid, reduced fat (~1%)|5|0|3.5|1.2|5|l
Milk, cow, fluid, reduced fat (~1.5%), added omega 3 polyunsaturates|5.3|0|4.7|1.4|5.3|l
Milk, cow, fluid, skim (~0.15% fat)|5.5|0|3.7|0|5.5|l
Milk, cow, fluid, prepared from dry powder, skim, standard dilution|6.2|0|3.9|0.1|6.2|l
Milk, cow, fluid, lactose free, regular fat (~3.5%)|4.8|0|3.2|3.2|4.6|l
Milk, cow, fluid, lactose free, reduced fat (~1%)|5.1|0.1|3.4|1.2|4.9|l
Milk, cow, canned, evaporated, regular|10.7|0|8.1|8.8|10.7|l
Milk, cow, canned, evaporated, reduced fat (~2%)|11.4|0|8.4|2.3|11.4|l
Milk, cow, canned, sweetened, condensed, regular|71|0|10.7|11.9|71|l
Milk, cow, fluid, flavoured, chocolate, reduced fat|9|0|3.5|2|9|l
Milk, human/breast, mature, fluid|7.1|0|1.4|4.4|7.1|l
Custard, vanilla, regular fat|16.4|1.3|3.5|3.4|13.8|l
Custard, vanilla, prepared from dry mix|15.6|0|4|4.1|10.8|l
Yoghurt, natural, regular fat (~3%)|5.2|0.1|5.3|3.3|5.1|l
Yoghurt, natural, sheep's milk|2.3|0|6.1|6.8|2.3|l
Yoghurt, flavoured, high fat (~5%)|13.4|0.6|4.6|5.1|13|l
Yoghurt, apricot pieces or flavoured, regular fat (~3%)|11.5|0.1|4.4|3.3|11.5|l
Yoghurt, strawberry pieces or flavoured, regular fat (~3%)|12.1|0.1|5.1|3.2|12.1|l
Yoghurt, flavoured, low fat (~2%)|12.7|0.3|4.8|1.4|11.8|l
Yoghurt, vanilla flavoured (~2%)|15|0.2|5.2|2|14|l
Yoghurt, flavoured, low fat (0.2%), intense sweetened, increased protein|2.4|0.4|10|0.3|1.7|l
Yoghurt, vanilla flavoured, low fat (<0.5%)|14.5|0.2|6.1|0.5|13.2|l
Yoghurt, almond based, flavoured|9.6|1.9|2.7|4.7|5.7|l
Yoghurt, coconut based, flavoured|9.6|1.4|1.1|9.4|4.9|l
Yoghurt, soy based, berry flavoured, regular fat (~3%)|13.1|0.2|3.8|3.8|8.7|l
Yoghurt, soy based, vanilla flavoured, reduced fat (~1%)|13.1|0.2|3.8|0.8|8.7|l
Dairy or yoghurt dessert, chocolate, regular fat|15.2|1.4|2.8|4.7|12.1|l
Almond beverage, added sugar, unfortified|3.2|0.6|0.5|1.5|2.3|l
Almond beverage, no added sugar, unfortified|0|0.5|0.5|1.5|0|l
Almond beverage, added sugar & Ca|3.2|0.6|0.5|1.5|2.3|l
Almond beverage, no added sugar, added Ca|0|0.5|0.5|1.5|0|l
Almond beverage, added sugar & vitamins B1, B2, B12, C & Ca|3.2|0.6|0.5|1.5|2.3|l
Coconut beverage, unfortified|2.5|0|0.2|1.8|1|l
Coconut beverage, added Ca|2.5|0|0.2|1.8|1|l
Oat beverage, fluid, unfortified|10.7|0.3|1.4|0.8|3.2|l
Oat beverage, fluid, added Ca|10.7|0.3|1.4|0.8|3.2|l
Oat beverage, fluid, added calcium & vitamins|6.2|0.5|0.5|1.7|1.7|l
Rice beverage, unfortified|9.4|0|0.6|1|3.2|l
Rice beverage, fluid, added calcium|13.4|0|0.3|1|3.9|l
Soy beverage, regular fat (~3%), unfortified|4.8|0.6|3.8|2.8|2.7|l
Soy beverage, regular fat (~3%), added Ca|4.8|0.6|3.8|2.8|2.7|l
Soy beverage, regular fat (~3%), added Ca & vitamins A, B1, B2 & B12|4.8|0.6|4.5|2.8|2.7|l
Soy beverage, reduced fat (~1%), added Ca & vitamins A, B1, B2 & B12|5.9|0.6|3.1|0.9|1.8|l
Soup, chicken & noodle, cup of soup, prepared from instant dry mix with water|3.5|0.3|0.7|0.2|0.4|l
Oil, almond|0|0|0|92|0|l
Oil, avocado|0|0|0|92|0|l
Oil, canola|0|0|0|92|0|l
Oil, coconut|0|0|0|91.6|0|l
Oil, copha|0|0|0.2|91.9|0|l
Oil, cottonseed|0|0|0|92|0|l
Oil, grapeseed|0|0|0|92|0|l
Oil, hemp|0|0|0|91.9|0|l
Oil, linseed or flaxseed|0|0|0|92|0|l
Oil, macadamia|0|0|0|92|0|l
Oil, maize|0|0|0|92|0|l
Oil, mustard seed|0|0|0|92|0|l
Oil, olive|0|0|0|91.9|0|l
Oil, palm|0|0|0|92|0|l
Oil, peanut|0|0|0|92|0|l
Oil, rice bran|0|0|0|92|0|l
Oil, safflower|0|0|0|92|0|l
Oil, sesame|0|0|0|92|0|l
Oil, soybean|0|0|0|92|0|l
Oil, sunflower|0|0|0|92|0|l
Oil, blend of polyunsaturated vegetable oils|0|0|0|92|0|l
Oil, blend of monounsaturated vegetable oils|0|0|0|92|0|l
Oil, vegetable|0|0|0|92|0|l
Apricot, canned in pear juice, juice only|9.1|0|0.5|0|9.1|l
Cherry, black, canned in syrup, syrup only|16.6|0|0.5|0|16.6|l
Mandarin, canned in syrup, syrup only|13.7|0|0.3|0|13.7|l
Peach, canned in pear juice, juice only|9.1|0.3|0.4|0|9.1|l
Pear, canned in pear juice, juice only|9.5|0.2|0.3|0|9.5|l
Pineapple, canned in pineapple juice, juice only|11|0|0.3|0|11|l
Pineapple, canned in syrup, syrup only|23.8|0|0.3|0|23.8|l
Plum, dark, canned in syrup, syrup only|29.6|0|0.4|0|29.5|l
Raspberry, canned in syrup, syrup only|24.3|0|0.3|0|24.3|l
Strawberry, canned in syrup, syrup only|26.3|0|0.2|0.3|26.1|l
Fruit salad, canned in fruit juice, juice only|10.6|0.2|0.4|0|10.6|l
Dip, hummus (hommus), commercial|9.4|3.9|6.9|19.5|0.8|l
Coconut, fresh, mature, water or juice|4.7|0|0.5|0.1|4.7|l
Coconut, fresh, young or immature, water or juice|6.6|0|0.2|0|6.6|l
Water, coconut, commercial|3.2|0|0.2|0|3.2|l
Coconut, cream, regular fat|1.4|0|1.6|23.7|1.4|l
Coconut, milk, canned, regular fat|1.7|0|1.4|15.7|1.7|l
Coconut, milk, canned, reduced fat|0.9|0|0.6|7.2|0.9|l
Tahini, sesame seed pulp|1|13.5|20.4|60.7|0.7|l
Glucose, liquid or syrup|121.6|0|0|0|45.9|l
Honey|103.4|0|0.4|0|103.4|l
Syrup, golden|107.8|0|0.4|0|107.8|l
Syrup, maple, pure|89.1|0|0|0.1|80.4|l
Syrup, malt|99.8|0|8.2|0|65.2|l
Syrup, rice malt|109.8|0|0.4|0|72.5|l
Treacle or molasses|98.3|0.6|0.8|0.1|98.3|l
"""
)
