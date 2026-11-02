package app.aaps.ui.compose.smartMealDialog.food

// Made by ui/tools/afcd_to_kotlin.py. Do not edit by hand.
//
// Australian Food Composition Database - Release 3, (c) Food Standards Australia New Zealand,
// https://www.foodstandards.gov.au/science-data/food-nutrient-databases/afcd, used under the
// Creative Commons Attribution 4.0 licence. Only the name, available carbohydrate, fibre, protein
// and fat of each food are kept. FSANZ does not endorse this app.

/**
 * One food per line: name|carbs|fibre|protein|fat, grams per 100 g. A last field "l" means per 100 mL.
 * Carbs are available carbohydrate without sugar alcohols, as on Australian labels.
 */
internal val australianFoodData: List<String> = listOf(
    """
Cardamom seed, dried, ground|34.4|28|10.8|6.7
Cinnamon, dried, ground|27.5|53.1|4|1.2
Chilli (chili), dried, ground|12.9|34.2|12.3|14.3
Cloves, dried, ground|31.6|33.9|6|13
Coriander seed, dried, ground|8.4|41.9|12.4|17.8
Cumin (cummin) seed, dried, ground|33.7|10.5|17.8|22.3
Curry powder|2.8|53.2|14.3|14
Fenugreek seed, dried|4.2|47.6|23|6.4
Ginger, dried, ground|57.5|14.1|9|4.2
Mustard powder|17.2|5|29.4|39.5
Nutmeg, dried, ground|28.5|20.8|5.8|36.3
Oregano, dried|26.4|42.5|9|4.3
Paprika, dry powder|19.1|34.9|14.1|12.9
Pepper, black, ground|38.6|25.3|10.4|3.3
Rosemary, dried|20.4|42.6|4.9|15.2
Sage, dried|20.4|40.3|10.6|12.8
Thyme, dried, ground|26.9|37|9.1|7.4
Turmeric, dried, ground|44.4|22.7|9.7|3.2
Salt substitute, potassium chloride|0|0|0|0
Salt, table, iodised|0|0|0|0
Salt, table, non-iodised|0|0|0|0
Stock, dry powder or cube|22.5|0|11.3|8.9
Stock, liquid, all flavours (except fish), prepared from commercial powder or cube|0.4|0|0.2|0.2
Taco seasoning mix, chilli-based|58.1|8.7|6.2|4.8
Baking powder, dry powder|19.8|0.4|0.8|0.3
Baking soda (bicarbonate), dry powder|0|0|0|0
Cream of tartar, dry powder|0|0.2|0|0
Lecithin, soy, granules|4.9|15.1|6|62.4
Gelatine, all types|0|0|84.4|0.4
Vanilla, artificial or imitation|14.4|0|0|0
Vanilla bean extract|12.6|0|0.1|0.1
Yeast, dry powder|6.3|26.9|36.4|5
Beer, high alcohol (5% v/v & above)|2.1|0|0.5|0
Beer, full strength (alcohol 4-4.9% v/v)|2.3|0|0.4|0
Beer, full strength (alcohol 4-4.9% v/v), carbohydrate modified|1|0|0.3|0
Beer, mid-strength (alcohol 3-3.9% v/v)|2.2|0|0.3|0
Beer, light (alcohol 1-2.9% v/v)|2.2|0|0.2|0
Alcoholic beverage, spirit, ~30% v/v, all (Brandy, Gin, Rum, Vodka and Whisky), cooked|0.3|0|0|0
Alcoholic beverage, spirit, ~40% v/v, all (Brandy, Gin, Rum, Vodka and Whisky)|0.1|0|0|0
Cider, apple (alcohol ~4-5% v/v)|5.6|0|0|0
Mirin|26.6|0|0.3|0
Wine, red|0.2|0|0.2|0
Wine, red, cabernet sauvignon|0.2|0|0.3|0
Wine, red, merlot|0.4|0|0.3|0
Wine, red, pinot noir|0.2|0|0.2|0
Wine, red, shiraz|0.2|0|0.2|0
Wine, red, sparkling|2|0|0.2|0
Wine, red, cooked|0.2|0|0.3|0
Wine, rose|0.7|0|0.2|0
Wine, white, sauvignon blanc|0.1|0|0.2|0
Wine, white, semillon|0.2|0|0.2|0
Wine, white, chardonnay|0.3|0|0.3|0
Wine, white, riesling|0.4|0|0.2|0
Wine, white|0.2|0|0.3|0
Wine, white, cooked|0.3|0|0.2|0
Wine, white, sparkling|1|0|0.2|0
Wine, white, sweet dessert style|14.7|0|0.4|0
Wine, fortified, port|12.4|0|0.2|0
Wine, fortified, sherry, dry style (~1% sugars)|1.2|0|0.1|0
Wine, fortified, sherry, sweet style (~11% sugars)|11.1|0|0.2|0
Beverage base, chocolate flavour, added vitamins & minerals (Milo)|60.1|6.8|12.4|9.9
Beverage base, chocolate flavour, unfortified (Nesquik brand)|83.2|5.8|5.3|2.8
Beverage base, drinking chocolate, unfortified|76.3|1.5|5.6|4.8
Cocoa powder|10.5|27.7|22.1|15.2
Beverage, chocolate flavour, from Milo powder, with regular fat cow's milk|10.2|0.6|4.1|4
Beverage, chocolate flavour, from Nesquik powder, with regular fat cow's milk|9.7|0.3|3.4|3.4
Beverage, chocolate flavour, from drinking chocolate, with regular fat cow's milk|10.2|0.1|3.5|3.5
Protein powder, hemp seed based|4.4|27.8|35.8|13.5
Protein powder, whey based, protein >70%, unfortified|1.6|1.8|85|2.2
Protein powder, whey based, protein >70%, unfortified, prepared with water|0.3|0.3|14.2|0.4
Coffee, instant, dry powder or granules|3.6|20.6|18|0.3
Coffee, instant, dry powder or granules, decaffeinated|3.6|20.6|18|0.3
Coffee mix, with beverage whitener & sugar, dry powder|56.1|3.6|13.1|12.7
Coffee, prepared from coffee mix with sugar & whitener, no added milk|2.8|0.2|0.6|0.6
Coffee, black, from instant coffee powder|0.2|0.1|0.2|0
Coffee, black, from instant coffee powder, decaffeinated|0.2|0.1|0.2|0
Coffee, espresso, from ground coffee beans|0|0|1.7|0.3
Coffee, long black, from ground coffee beans|0|0|0.3|0.1
Coffee, flat white/latte/cappuccino, from ground coffee beans, with regular fat cow's milk|3.4|0|2.8|2.6
Cordial base, 25% citrus fruit juice, regular|37.3|0|0|0
Cordial base, 40% citrus fruit juice, regular|40.2|0|0.2|0
Cordial base, blackcurrant juice, regular|45|0|0.1|0
Cordial base, fruit juice/flavours, intense sweetened or diet|7.1|0|0|0
Cordial, 25% citrus fruit juice, regular, recommended dilution|8.7|0|0|0
Cordial, 40% citrus fruit juice, regular, recommended dilution|9.4|0|0|0
Cordial, blackcurrant juice, regular, recommended dilution|9.3|0|0|0
Cordial, fruit juice/flavours, recommended dilution|6.5|0|0|0
Cordial, fruit juice/flavours, intense sweetened or diet, recommended dilution|0.7|0|0|0
Juice, apple, commercial, added vitamin C|7|0.2|0.2|0
Juice, apple & blackcurrant, commercial|9.3|0|0.2|0
Juice, lemon|1.8|2|0.6|0.1
Juice, lime|1.2|3.6|0.8|0.1
Juice, orange, commercial|6.8|0.3|0.8|0
Juice, orange & mango, commercial|7.7|0.2|0.6|0
Juice, vegetable blend, commercial|2.7|0.6|0.9|0
Fruit drink, apple juice|8.2|0|0.1|0
Fruit drink, blackcurrant, commercial|9.8|0|0.2|0
Fruit drink, cranberry|12.2|0.1|0|0
Fruit drink, orange juice|9.6|0.1|0.2|0
Soft drink, cola flavour|12.3|0|0|0.2
Soft drink, cola flavour, decaffeinated|12.3|0|0|0.2
Soft drink, cola flavour, intense sweetened or diet|0|0|0|0
Soft drink, cola flavour, intense sweetened or diet, decaffeinated|0|0|0|0
Soft drink, dry ginger ale or ginger beer, regular|9.8|0|0|0
Soft drink, fruit flavours|8.8|0|0|0
Soft drink, tonic water|8.8|0|0|0
Soft drink, fruit flavours, intense sweetened or diet|0|0|0|0
Soft drink, tonic water, intense sweetened or diet|0|0|0|0
Soft drink, energy drink, Red Bull|10.8|0|0.1|0
Soft drink, energy drink, V|10.8|0|0.1|0
Kombucha, flavoured, added juice or intense sweeteners|0.9|0.1|0|0
Tea, regular, black, brewed from leaf or teabags, without milk|0|0|0.1|0
Tea, black, brewed from leaf or teabags, decaffeinated|0|0|0.1|0
Tea, green, plain, without milk|0|0|0.2|0
Mineral water, natural, unflavoured|0|0|0|0
Mineral water, citrus flavoured|3.7|0|0|0
Water, bottled, still|0|0|0|0
Water, soda|0|0|0|0
Water, tap|0|0|0|0
Biscuit, savoury, corn cake, plain, salted|71.5|5.9|9.8|3
Biscuit, savoury, rice cracker, plain|78.8|0.9|7|4.3
Biscuit, savoury, rice cracker, added vegetable powder|73.8|2|6.6|7.4
Biscuit, savoury, rice cake, from brown rice, plain|76.3|3.5|8.8|3.5
Biscuit, savoury, rice cracker, flavoured (excluding seaweed)|74.1|1.5|7.1|6.7
Biscuit, savoury, rice cracker, seaweed flavoured|75.3|1.6|9.6|1.2
Biscuit, savoury, from rye flour, crispbread|60.6|16.5|8.8|1.3
Biscuit, savoury, seed based|6|14.6|20.1|49.6
Biscuit, savoury, from white wheat flour, flaky cracker style|63.6|3|11.5|20.7
Biscuit, savoury, from white wheat flour, plain snack cracker style|66.3|3.4|8.1|20.7
Biscuit, savoury, from white wheat flour, Salada style|63.1|3.6|9.2|14.2
Biscuit, savoury, from white wheat flour, water cracker style|69.4|3.7|10.4|7.2
Biscuit, savoury, from white wheat flour, cheese-flavoured|56.4|2.9|12|24.2
Biscuit, savoury, from white wheat flour, flavoured (excluding cheese)|59.7|2|9.6|24.5
Biscuit, savoury, from wholemeal wheat flour, crispbread|59.1|9.9|10.6|8.1
Biscuit, savoury, from wholemeal wheat flour & rye flour, crispbread, puffed|69.1|7.6|14|3.8
Biscuit, savoury, from wheat flour, crispbread, puffed & toasted|66.1|3.3|12.3|5.6
Biscuit, sweet, Anzac style, commercial|60.3|3.8|5.8|22.1
Biscuit, sweet, Anzac style, homemade from basic ingredients|64|3.1|4.6|22.6
Biscuit, sweet, ginger flavoured, commercial|77.7|1.9|4.8|10.6
Biscuit, sweet, plain|69.4|2.1|6.4|15.8
Biscuit, sweet, plain, with icing, commercial|77.5|1.7|4.6|11.2
Biscuit, sweet, shortbread style, commercial|60.8|2.3|6.2|26.3
Biscuit, sweet, wheatmeal|67|5.6|7.8|11.7
Biscuit, sweet, biscuit base, caramel filling, chocolate-coated, commercial|61.8|1.8|5.6|19.7
Biscuit, sweet, biscuit base, mint filling, chocolate-coated|60.6|0.6|6.3|29.4
Biscuit, sweet, chocolate chip or coated|61.9|3|5.8|22.7
Biscuit, sweet, chocolate chip, homemade from basic ingredients, fat not further defined|66|1|5.1|21.7
Biscuit, sweet, chocolate flavoured, commercial|72.4|1.9|6.2|17.2
Biscuit, sweet, cream filled, commercial|62.8|2.7|5.4|21.5
Biscuit, sweet, sandwich, cream & jam filling|64.1|1.8|4|22.5
Biscuit, sweet, sandwich, cream filling, chocolate-coated, tim tam style|63.1|2.2|5.4|25.4
Biscuit, sweet, breakfast style, with or without dried fruit|64.6|6.4|7.5|15.6
Cone, wafer style, for ice cream|79.7|3.2|9.1|4
Bread, flat (pita or Lebanese), white, commercial|49.1|3.7|9.5|2.1
Bread, flat (pita or Lebanese), wholemeal, commercial|47.2|6.4|8.6|2
Bread, Naan, commercial|47.8|2.6|8|7.1
Bread, tortilla, white, commercial|46.3|2|7.3|9
Bread, wrap, white, commercial|46.9|3|7.8|8.4
Bread, flat wrap or tortilla, corn, commercial|44.8|5|4.8|3.2
Breadcrumbs, white|68.5|4.7|14|4.1
Bagel, from white flour, commercial|50.7|3.1|10.4|1.4
Bagel, from white flour, commercial, toasted|57.6|3.5|11.8|1.6
Bread, damper, from white flour, homemade|42.9|3.3|7.5|5.5
Bread, damper, homemade, toasted|50.5|3.9|8.8|6.5
Bread, from white flour|46.2|3.2|9.5|2
Bread, from white flour, toasted|54.3|4.3|10.8|2.8
Bread, from white flour, Italian-style, commercial|46.8|3.9|9|2.5
Bread, from white flour, Italian-style, commercial, toasted|55.7|4.6|10.7|3
Bread, from white flour, Turkish|45.5|3.6|7.8|2.8
Bread, from white flour, Turkish, toasted|54.2|4.3|9.2|3.3
Bread, from white flour, sour dough, commercial|42.9|3.1|8.8|1.4
Bread, from white flour, sour dough, toasted|51.1|3.7|10.4|1.7
Bread, organic|40.3|5.6|11.6|2.9
Bread, organic, toasted|47.4|6.6|13.7|3.4
Bread, pizza base, commercial|48.5|2.7|8.6|3.3
Bread roll, from white flour|44.6|3|9.3|2.9
Bread, from wholemeal flour|39.2|6.9|10.4|2.5
Bread, from wholemeal flour, toasted|46.1|8.1|12.2|2.9
Bread roll, from wholemeal flour|45.2|5.8|10.1|3
Bread, mixed grain|39.8|5.8|10.7|4.5
Bread, mixed grain, toasted|46.8|6.9|12.6|5.3
Bread roll, mixed grain|40.3|5.6|12.2|3
Bread, from rye flour, sour dough|42.7|5.2|9.2|1.6
Bread, from rye flour, sour dough, toasted|50.2|6.1|10.8|1.9
Bread, from white flour, added fibre|43.6|6.4|9.7|1.7
Bread, from white flour, added fibre, toasted|51.9|7.7|11.5|2
Bread, from white flour, added omega-3 polyunsaturates|46.6|3.1|9.1|2.1
Bread, from white flour, added omega-3 polyunsaturates, toasted|54.2|3.6|10.6|2.4
Bread, from white Jackaroo flour, added fibre and vitamins B1 & folate & Fe|45.7|6.5|8.3|2.3
Bread, from white Jackaroo flour, added fibre and vitamins B1 & folate & Fe, toasted|53.8|7.6|9.8|2.7
Bread, gluten free|36.6|3.9|5.5|3.9
Bread, gluten free, toasted|43.6|4.6|6.5|4.6
Bread, garlic, commercial, baked|42.8|3|8|15.1
Bread, garlic or herb, homemade, cooked|40.5|3.6|8.8|11.4
Bread roll, topped with cheese|35.8|2.4|12.4|9
Bread roll, topped with cheese & bacon|27|1.8|16.6|11.2
Bread, brioche|46.5|2.4|9.5|10.6
Bread, from white flour, commercial, added dried fruit|47.3|3.6|8.8|3.7
Bread, from white wheat flour, added dried fruit, toasted|52.6|4|9.8|4.1
Bun, sweet, hot cross bun, with dried fruit|50.2|4.1|7.2|3.2
Bun, sweet, with dried fruit, uniced|55.2|3|6.5|5
Bun, sweet, with dried fruit, iced|51.8|1.9|6.4|6.1
Crumpet, from white flour, toasted|43.3|3|7.1|1.2
Muffin, English style, from white flour|38.7|3.2|10.1|2.1
Muffin, English style, from white flour, toasted|42.1|3.4|11|2.3
Muffin, English style, from white flour, added dried fruit, toasted|46.4|3.6|9.7|2
Breakfast cereal, beverage, non-chocolate flavours, added vitamins & minerals (Up & Go)|12.4|0.8|3.1|1.5
Breakfast cereal, flakes of corn, unfortified|75.6|4.6|7.3|2.9
Breakfast cereal, flakes of corn, added vitamins & minerals (Kellogg's Cornflakes)|81.8|3.8|7.9|1.8
Breakfast cereal, mixed grain (wheat, rice & oat), flakes, honey, unfortified (Be Natural Honey Flakes)|65|12.5|8.4|4.1
Breakfast cereal, mixed grain (rice & wheat), flakes, added vitamins & minerals (Kellogg's Special K)|70.5|2.7|18.2|4.1
Breakfast cereal, mixed grain (wheat, oat & corn), added vitamins & minerals (Kellogg's Nutri-grain)|62.8|2.5|23.3|2.5
Breakfast cereal, mixed grain (wheat & oat), flakes, apricot & sultana, added vitamins & minerals (Kellogg's Just Right)|71.6|8.2|9.1|1.5
Breakfast cereal, puffed or popped rice, no added sugar or salt, unfortified|77.6|2.3|8.5|3
Breakfast cereal, puffed or popped rice, added vitamins & minerals (Kellogg's Rice Bubbles)|81.2|0.9|6.8|1.1
Breakfast cereal, puffed or popped rice, cocoa coating, added vitamins & minerals (Kellogg's Coco Pops)|83.2|1.9|5.3|2.9
Breakfast cereal, whole wheat, puffed, no added sugar or salt, unfortified|62.4|9.2|10|2.8
Breakfast cereal, whole wheat, biscuit, no added sugar, unfortified|63.7|11.5|10.4|2.7
Breakfast cereal, wheat bran, pellets, added vitamins & minerals (Kellogg's All Bran)|41.7|27.3|14.2|5
Breakfast cereal, wheat bran, flakes, added vitamins & minerals (Kellogg's Bran Flakes)|58.4|19.3|12.2|3.6
Breakfast cereal, whole wheat, biscuit, added vitamins & minerals (Sanitarium Weet Bix)|61|11|11.9|2.3
Breakfast cereal, whole wheat, biscuit, bran, added vitamins & minerals (Sanitarium Weet-Bix Hi-Bran)|56.5|17.5|12.5|5.6
Breakfast cereal, whole wheat, flakes, added vitamins & minerals (Weeties style)|65|11.7|11.5|2
Breakfast cereal, whole wheat, flakes, dried fruit & nuts, added vitamins & minerals (Uncle Toby's Fibre Plus)|65.6|15.5|9.1|3.3
Breakfast cereal, wheat bran, flakes, sultanas, added vitamins & minerals (Kellogg's Sultana Bran)|63.3|14.8|9.8|1.5
Muesli, granola, toasted, added nuts & seeds, unfortified|52.3|7.6|11.2|17.6
Muesli, granola, non-oat based, toasted, added nuts & seeds, unfortified|53.5|6.6|11.9|18.8
Muesli, toasted, added dried fruit & nuts, unfortified|56.6|11.4|8.9|13.3
Muesli, untoasted or natural style, added dried fruit, unfortified|59.2|9.4|10.8|10.8
Porridge, rolled oats, prepared with water|11.4|2.9|3|2.9
Porridge, rolled oats, prepared with regular fat cow's milk|14.5|1.6|5.3|4.9
Porridge, rolled oats mixed with sugar or honey & other flavours, prepared with cow's milk|19.8|1.7|4.6|4.4
Bread, banana, commercial|43.1|1.5|5.7|11.1
Bread, banana, homemade|50|1.3|5.3|12.7
Slice, brownie, chocolate, without nuts, commercial|45.6|2.6|7.1|23.1
Slice, brownie, chocolate, with nuts, homemade|52.7|1.9|6.5|25.5
Slice, caramel|52.4|0.8|5.3|24.6
Cake mix, plain, dry powder|73.5|1.4|4.8|8.2
Cake, carrot, commercial, iced|47.6|1.5|4.4|20.4
Cake, carrot, homemade, uniced|45.3|2.1|4.8|16
Cake, carrot, homemade, iced|49.5|1.7|3.9|15
Cake, chocolate, homemade, uniced|41.3|2.9|7.9|19.4
Cake, chocolate, homemade, iced|46.4|2.6|6.6|18
Cake, fruit, homemade, uniced|49.4|2.8|3.8|10
Cake, lamington, unfilled|48|2.4|4.7|9.4
Cake, mud, dark chocolate, commercial, chocolate ganache icing|48.7|2.6|5.1|15.6
Cake, mud, dark chocolate, homemade, uniced|32.5|0.5|5.6|34.6
Cake, mud, dark chocolate, homemade, chocolate ganache icing|35.5|0.6|5.2|34.1
Cake, plain butter cake, commercial, uniced|46.9|1.3|8.2|15.2
Cake, plain butter cake, commercial, iced|52.7|1.4|5|16.1
Cake, plain butter cake, homemade, uniced|52.5|0.9|6.7|16.3
Cake, plain butter cake, homemade, iced|55.2|0.7|5.4|15.2
Cake, sponge, plain, commercial, uniced, unfilled|56.5|1.2|7.5|8.2
Cake, sponge, plain, commercial, uniced, filled with jam & cream|58|1.2|6.7|12.2
Cake or cupcake, sponge, plain, commercial, iced, unfilled|61.6|0.7|5|11
Muffin, cake-style, berry, commercial|44.4|1.9|5.2|15.6
Muffin, cake-style, chocolate chip, commercial|48.1|1.4|6.4|17.7
Doughnut, dusted with cinnamon & sugar|43.9|2|6.6|19.5
Doughnut, iced|48.2|1.7|6.7|15.9
Doughnut, jam filled, sugar coated|46.2|1.9|6|17.6
Pancake, plain, homemade|30.3|0.8|7.4|7.6
Pikelet, plain, homemade|47.9|1.7|7.5|5.9
Pikelet or pancake, sweet, commercial|38.4|2.1|5.7|4.3
Pudding, chocolate, homemade|37|1.1|3.6|2.6
Pudding, plum, steamed or boiled, homemade|54.1|2.4|4.3|11.1
Pudding, sticky date, homemade|48.5|1.6|3|16.8
Scone, plain, commercial|55.2|2.1|7.9|8.5
Scone, plain, homemade|50.7|1.4|8|9.5
Scone, fruit, commercial|59.9|2.5|6.4|6.6
Scone, pumpkin, homemade|47.9|1.7|7.4|7.4
Amaranth, grain, whole, uncooked|55.6|11.1|15.2|6.4
Barley, pearl, uncooked|60.6|13.1|9.9|3.1
Barley, pearl, boiled, no added fat or salt|25.1|5.4|4.1|1.3
Buckwheat groats, uncooked|58|5|12.8|3.2
Buckwheat groats, cooked in water, no added salt|12.3|1.1|2.7|0.7
Bulgur, uncooked|66.1|7.8|10.3|2
Bulgur, soaked in water, no added fat or salt|34.4|4.1|5.4|1.3
Sago, dry, uncooked|85.5|0.4|0.2|0.2
Sago, cooked in water, no added salt|13.5|0.1|0|0
Cornmeal (polenta), uncooked|70|3|8.5|1.7
Cornmeal (polenta), boiled, no added fat or salt|22.3|1|2.7|0.5
Couscous, uncooked|73.6|4.2|13.8|0.2
Couscous, boiled, no added fat or salt|32.7|1.9|6.1|0.1
Millet, uncooked|67.4|3.7|10.2|3.5
Millet, boiled, no added fat or salt|22.1|1.2|3.3|1.1
Maize, grits, uncooked|73.3|2.7|8.1|1.3
Oat bran, unprocessed, uncooked|45|16.7|16.7|9.3
Oats, hulled, uncooked|55.5|8.6|12.5|9.8
Oats, rolled, uncooked|54.5|9.5|12.2|9.5
Oats, rolled, mixed with sugar or honey & other flavours, uncooked|69.1|7.5|8.3|7.5
Psyllium, uncooked|1|88.7|3|1.3
Quinoa, black, uncooked|50.6|16.1|13.1|6.6
Quinoa, red, uncooked|55|13.8|14.1|6.9
Quinoa, white, uncooked|61.8|10.5|12.2|6.3
Quinoa, uncooked|58.6|12|12.9|6.5
Quinoa, cooked in water, no added salt|16.3|3.4|3.6|1.8
Rye, grain, whole, uncooked|51.3|17.3|11.1|2.6
Rye, rolled, uncooked|50.8|17.3|10.4|2.6
Sorghum, grain, uncooked|65.8|8.6|8.2|3.6
Teff, uncooked|38.4|8|13.3|2.4
Triticale, grain, uncooked|58.3|14.4|9.6|2.4
Semolina, uncooked|61.6|3.2|10.7|1.2
Semolina, boiled, no added fat or salt|19.6|1|3.4|0.4
Spelt, uncooked|59.2|8.8|11.7|3
Spelt, boiled, no added fat or salt|24.5|3.6|4.8|1.2
Tapioca, pearl or seed style, uncooked|79.3|0|0.2|0.2
Tapioca, pearl or seed style, boiled, no added fat or salt|11.5|0|0|0
Wheat bran, unprocessed, uncooked|22.6|41.8|14.8|4.1
Wheat, whole, uncooked|58.3|11.4|11.2|2.4
Wheat germ|32.7|20.1|20.6|6.1
Rice, white, uncooked|74|0.8|7|0.9
Rice, white, boiled or rice cooker, no added salt|34.7|0.8|3.1|0.2
Rice, white, purchased as 'instant', microwaved|32.5|1|3.4|0.6
Rice, brown, uncooked|69.1|3.5|7.6|3.1
Rice, brown, boiled, no added salt|33.5|1.7|4.1|1.1
Rice, wild, uncooked|68.7|6.2|14.7|1.1
Rice, wild, boiled, no added salt|19.5|1.8|4|0.3
Rice paper wrapper, soaked in water|32.2|1|3.3|0.9
Rice, white, fried with bacon or ham, egg, prawns & vegetables|31|1.2|5.5|5.2
Flour, arrowroot|78.6|3.4|0.4|0.2
Flour, cornflour, from maize starch|84.1|0|0.4|0.6
Flour, cornflour, from maize & wheat starch|84.4|0.5|0.6|0.4
Flour, chickpea (besan)|38.5|20.8|19.7|5.5
Flour, gluten free, plain|80.5|1.7|3.4|1.1
Flour, rice|71.6|2.2|7.3|2
Flour, rye|54.9|17.3|11.4|2.3
Flour, soya (soy)|14.6|16|49.8|8.9
Flour, spelt|59.1|7.4|18.7|2.4
Flour, wheat, white, high protein or bread making flour|69.6|2.5|11.7|1.7
Flour, wheat, white, plain|73.2|2.2|10|1.3
Flour, wheat, white, plain, added vitamins B1, B2, B3, B6, E & folate & Fe, Mg & Zn (Jackaroo)|67.3|3.8|11.1|1.5
Flour, wheat, white, self-raising|73.2|2.2|10|1.3
Flour, wheat, white, self-raising, added vitamins B1, B2, B3, B6, E & folate & Fe, Mg & Zn (Jackaroo)|65.2|3.7|10.7|1.4
Flour, wheat, wholemeal, plain|61.6|8.8|11.6|1.6
Flour, wheat, wholemeal, self-raising|65.3|11.3|11.4|2.1
Gluten, from wheat (vital wheat gluten)|13.2|0.6|75.2|0
Starch, potato|74.5|5.9|0|0.5
Hamburger, white roll, beef patty, with cheese, onion, pickles & sauce, fast food chain|23.2|1.8|14.7|12.6
Hamburger, white roll, beef patty, with salad, independent takeaway outlet|18|2.6|12.1|9.2
Hot dog, bread roll, frankfurt & sauce filling|27.4|2.5|10.6|7.6
Pizza, cheese & tomato, commercial|28.7|2.8|16.2|11
Pizza, ham & pineapple, purchased frozen, baked|28.2|3.1|11.5|6
Pizza, ham & pineapple, takeaway style|23.1|3.1|12.8|8.3
Pizza, supreme, takeaway style|20.5|3|13|9.8
Pizza, supreme, fast food chain|27.6|2.5|12.9|9
Pizza, supreme, purchased frozen, baked|26.3|3.2|14|11.6
Pizza, vegetable, fast food chain|27.2|2.8|9.3|6
Noodle, rice stick, boiled, drained|26.1|0.7|2.5|0
Noodle, soba, dry|71.2|5.3|11.9|1.3
Noodle, soba, boiled, drained|21.5|1.6|3.6|0.4
Noodle, wheat, fresh, soaked, drained|26.5|1.7|6.3|1
Noodle, wheat, instant, flavoured, dry, uncooked|54|3.7|11|19.1
Noodle, wheat, instant, unflavoured, boiled, drained|17.6|1.2|3.8|6.2
Noodle, wheat, instant, unflavoured, boiled, undrained|6.6|0.4|1.4|2.3
Noodle, wheat, instant, unflavoured, dry, uncooked|54|3.7|11|19.1
Noodle, wheat, instant, flavoured, boiled, drained|17.6|1.2|3.8|6.2
Noodle, wheat, instant, flavoured, boiled, undrained|6.6|0.4|1.4|2.3
Noodle, wheat with egg, plain, boiled, no added fat|29.1|2.6|7.5|1.4
Pasta, gluten free, boiled from dry, no added salt|32.4|1.2|3.1|0.7
Pasta, legume based, boiled, no added salt|16.1|6.2|14.4|1.7
Pasta, white wheat flour, dry|68.5|3.2|12.7|1.4
Pasta, white wheat flour, boiled from dry, no added salt|26.2|2.2|5.3|0.4
Pasta, white wheat flour, fresh, uncooked|49.4|3.3|10.5|2.1
Pasta, white wheat flour, fresh, boiled, no added salt|27.1|2.7|5.9|1.3
Pasta, white wheat flour & egg, dry|74.4|2.2|10.6|1.6
Pasta, white wheat flour & egg, boiled from dry, no added salt|29.1|2.6|7.5|1.4
Pasta, white wheat flour & spinach, dry|73.1|4.4|11.9|1.3
Pasta, white wheat flour & spinach, boiled from dry, no added salt|27.3|1.4|3.8|0.4
Pasta, wholemeal wheat flour, dry|60.6|9.9|12.5|2.2
Pasta, wholemeal wheat flour, boiled from dry, no added salt|24.6|5.7|5.4|0.8
Pasta in cream based sauce, dry mix|69.7|3.6|13.1|3.1
Pasta in cream based sauce, prepared from dry mix|23.6|1.1|5.6|2.6
Lasagne, beef, commercial, purchased frozen, baked|11.8|2.1|6.6|6.6
Lasagne, beef, homemade|16.2|1.4|9.9|8
Macaroni & cheese, homemade, cooked unfilled pasta, homemade cheese sauce|21.5|1.7|6.4|5.5
Pasta dish, homemade, cooked unfilled pasta, homemade beef bolognese sauce & added vegetables|15.2|1.7|7.3|2.8
Spaghetti in tomato & cheese sauce, canned|10.7|0.8|2.5|0
Spaghetti in tomato & cheese sauce, canned, reduced salt|10.7|0.8|2.5|0
Croissant, plain|35.6|2.7|10|19.1
Pastry, filo, commercial, raw|55.8|2.5|10.2|3.8
Pastry, filo, commercial, baked|75.1|4.3|12.2|3.1
Pastry, puff, with butter, commercial, raw|32.9|3.4|5.5|19
Pastry, puff, with butter, commercial, baked|42.7|4.4|7.1|24.7
Pastry, puff, vegetable oil, commercial, raw|35|3.4|5.5|16.4
Pastry, puff, vegetable oil, commercial, baked|44.9|4.4|7|21
Pastry, shortcrust style, commercial, raw|41.3|6.4|6.4|21.4
Pastry, shortcrust style, commercial, baked|50.4|7.8|7.8|26.1
Pastry, filled with spinach & cheese, purchased frozen, baked|24.7|2.5|9.4|16.4
Pie, savoury, chicken & vegetable, commercial|25|2.1|8.2|11
Pie, savoury, meat, commercial, ready to eat|21.2|2.4|8.9|11.4
Pie, savoury, meat, purchased frozen, baked|19.3|2|8.6|11.3
Sausage roll, commercial, ready to eat|27.2|3|9.7|14.6
Sausage roll, purchased frozen, baked|27.2|3|9.7|12.7
Spring roll, meat & vegetable filling, commercial, deep fried|27.5|2.8|7.5|11.7
Pastry, danish, sweet, all types|35.6|3.7|7.5|15
Pie, sweet, apple, commercial|31.4|1.3|3|12.9
Tart, custard, commercial|30.2|1|6.3|10.7
Dressing, French or Italian, regular fat, commercial|11.7|0|0.1|19.6
Dressing, thousand island, regular fat, commercial|16.6|1.4|1|31.8
Mayonnaise, traditional (>65% fat), commercial|8.7|0.5|0.8|67.2
Mayonnaise, low fat, commercial|27.1|0|0.5|1.8
Gravy powder, dry mix|54.7|0.1|8.9|6.2
Gravy, prepared from dry powder with water|4.5|0|0.7|0.5
Mustard, cream style|6.2|4.1|5.8|3.1
Paste, Indian style curry, commercial|14.2|5.4|2.9|14.1
Paste, green curry, commercial|6.3|3|2.4|7.9
Paste, soybean|19.7|4.3|12.9|5.1
Paste, shrimp|0|0|21|1
Sauce, barbecue, commercial|42.2|0.9|0.8|0.1
Sauce, butter chicken, commercial|5.8|1.9|1.3|7.6
Sauce, cranberry, commercial|40|1.1|0|0
Sauce, fish, commercial|4.4|1.2|6.8|0.3
Sauce, hoi sin (hoisin), commercial|37.2|10.4|1.6|5.4
Sauce, oyster, commercial|21|0.3|2.8|1
Sauce, pasta or simmer, commercial, low fat|6.6|1.3|0.8|2.9
Sauce, pasta, basil pesto, commercial|7.4|1.5|5.3|37.1
Sauce, pasta, bolognese, homemade using beef mince & homemade tomato based sauce|3.8|1.4|8.8|4.5
Sauce, pasta, bolognese, homemade using beef mince & commercial tomato based sauce|3.9|1.1|10|6.2
Sauce, pasta, cheese or cream-based, commercial|5.3|0.1|1.4|6.8
Sauce, pasta, tomato-based, commercial, heated|7.5|1.8|1.6|1.5
Sauce, plum, commercial|56.5|0.6|0.1|0.8
Sauce, rogan josh, commercial|6.2|1.9|1.5|4.1
Sauce, salsa, tomato-based, commercial|6.3|1.6|1.3|0
Sauce, simmer for chicken, commercial|4.8|1.7|0.8|7.5
Sauce, simmer, curry flavoured, commercial|10.7|2.3|2.1|6.9
Sauce, soy, commercial|3.2|0|6.8|0
Sauce, soy, commercial, reduced salt|3.2|0|6.8|0
Sauce, tabasco, commercial|0.1|0.6|1.3|0.8
Sauce, tomato, commercial|24.1|1.3|1.3|0
Sauce, tomato, commercial, reduced salt|24.1|1.3|1.3|0
Sauce, tomato, reduced sugar & salt, commercial|11.3|1.3|1.3|0
Sauce, white, savoury, homemade|10.8|0.2|3.9|13.5
Sauce, Worcestershire, commercial|17.6|0|1.3|0.2
Vinegar (except balsamic vinegar)|0|0|0|0
Vinegar, balsamic|15|2.1|0.5|0
Peanut butter, smooth & crunchy, added sugar & salt|14.3|5.8|22.2|49.6
Peanut butter, smooth & crunchy, no added sugar or salt|9.4|6.5|24.3|54.3
Spread, yeast, marmite|16.6|11.8|13.3|0.9
Spread, yeast, vegemite|10.9|6.5|24.4|0.9
Cheese, blue vein|0.3|0|19.1|32.9
Cheese, bocconcini|0|0|16.6|20.2
Cheese, brie|0|0|18.6|31.8
Cheese, camembert|0|0|18.6|27.9
Cheese, cheddar, natural, regular fat|0.3|0|24.6|33.4
Cheese, cheddar, natural, reduced fat (~25%)|0|0|27.9|23.4
Cheese, cheddar, processed, regular fat|1.9|0|18.2|23.9
Cheese, cheddar, processed, reduced fat (~15%), added vitamin D|4.9|0|22.4|15.4
Cheese, cottage|1.8|0|17|5.7
Cheese, cream, plain|0.2|0|8.5|34.2
Cheese, edam|0|0|27.4|27.2
Cheese, fetta (feta)|0.4|0|16.6|20.6
Cheese, goat, firm|0.8|0|18.6|31.3
Cheese, goat, soft|0.9|0|21.2|21.7
Cheese, haloumi (halloumi)|1|0|20.5|24.6
Cheese, haloumi (halloumi), reduced salt|1.3|0|19|25.3
Cheese, mozzarella|0|0|22.2|22.1
Cheese, parmesan, dried, finely grated|0|0|40.6|33.3
Cheese, parmesan, fresh, regular fat|0|0|32|27
Cheese, ricotta|2.7|0|10.4|11
Cheese, hard, dairy alternative|19.1|2.6|0.9|23.3
Cream, thickened, regular fat (~35%)|2.4|0|2|36.5
Cream, sour, regular fat|2|0|1.9|36.3
Cream, sour, light (~18% fat)|4.5|0|3.6|19.7
Cream, imitation (non-dairy)|17.7|0|1|21.8
Ice cream, vanilla flavour, regular fat|20.5|0|3.7|11.1
Ice cream, vanilla flavour, premium or rich (~15% fat)|22.5|0|3.2|13.5
Ice confection, stick, milk-based, various flavours|19.8|0.2|2.9|5.3
Ice confection, stick, water-based, various flavours|17.1|0|0.2|0
Ice confection, stick or tub, fruit juice or fruit flavoured|18.7|0|0.2|0.1
Milk, cow, fluid, regular fat (~3.5%)|5.4|0|3.3|3.4
Milk, cow, fluid, prepared from dry powder, regular fat, standard dilution|6.1|0|3.6|3.6
Milk, cow, fluid, regular fat (~3.5%), added omega 3 polyunsaturates|5.1|0|3.1|3.2
Milk, cow, fluid, reduced fat (~1%)|4.8|0|3.3|1.2
Milk, cow, fluid, reduced fat (~1.5%), added omega 3 polyunsaturates|5.1|0|4.5|1.4
Milk, cow, fluid, skim (~0.15% fat)|5.3|0|3.6|0
Milk, cow, fluid, prepared from dry powder, skim, standard dilution|6|0|3.7|0.1
Milk, cow, fluid, lactose free, regular fat (~3.5%)|4.7|0|3.1|3.1
Milk, cow, fluid, lactose free, reduced fat (~1%)|4.9|0.1|3.3|1.1
Milk, cow, canned, evaporated, regular|10|0|7.5|8.2
Milk, cow, canned, evaporated, reduced fat (~2%)|10.6|0|7.8|2.1
Milk, cow, canned, sweetened, condensed, regular|55|0|8.3|9.2
Milk, cow, fluid, flavoured, chocolate, reduced fat|8.6|0|3.3|1.9
Milk, cow, powder, regular fat|43|0|25.3|25.6
Milk, cow, powder, regular fat, unfortified|43|0|25.3|25.2
Milk, cow, powder, regular fat, added vitamins A & D|43|0|25.3|27.3
Milk, cow, powder, skim|56|0|34.9|0.8
Milk, human/breast, mature, fluid|6.8|0|1.3|4.2
Custard, vanilla, regular fat|14.6|1.2|3.2|3
Custard powder, vanilla, dry mix|81.5|0.5|0.5|0.4
Custard, vanilla, prepared from dry mix|13.9|0|3.6|3.7
Yoghurt, natural, regular fat (~3%)|5|0.1|5.1|3.2
Yoghurt, natural, sheep's milk|2.2|0|5.9|6.5
Yoghurt, flavoured, high fat (~5%)|12.9|0.6|4.4|4.9
Yoghurt, apricot pieces or flavoured, regular fat (~3%)|11.1|0.1|4.2|3.2
Yoghurt, strawberry pieces or flavoured, regular fat (~3%)|11.6|0.1|4.9|3.1
Yoghurt, flavoured, low fat (~2%)|12.2|0.3|4.6|1.3
Yoghurt, vanilla flavoured (~2%)|14.5|0.2|5|1.9
Yoghurt, flavoured, low fat (0.2%), intense sweetened, increased protein|2.3|0.4|9.6|0.3
Yoghurt, vanilla flavoured, low fat (<0.5%)|13.9|0.2|5.9|0.5
Yoghurt, almond based, flavoured|9.2|1.8|2.6|4.5
Yoghurt, coconut based, flavoured|9.2|1.3|1.1|9
Yoghurt, soy based, berry flavoured, regular fat (~3%)|12.6|0.2|3.6|3.7
Yoghurt, soy based, vanilla flavoured, reduced fat (~1%)|12.6|0.2|3.6|0.8
Dairy or yoghurt dessert, chocolate, regular fat|14.6|1.4|2.6|4.5
Almond beverage, added sugar, unfortified|3|0.6|0.5|1.4
Almond beverage, no added sugar, unfortified|0|0.5|0.5|1.4
Almond beverage, added sugar & Ca|3|0.6|0.5|1.4
Almond beverage, no added sugar, added Ca|0|0.5|0.5|1.4
Almond beverage, added sugar & vitamins B1, B2, B12, C & Ca|3|0.6|0.5|1.4
Coconut beverage, unfortified|2.4|0|0.2|1.7
Coconut beverage, added Ca|2.4|0|0.2|1.7
Oat beverage, fluid, unfortified|10.2|0.3|1.3|0.8
Oat beverage, fluid, added Ca|10.2|0.3|1.3|0.8
Oat beverage, fluid, added calcium & vitamins|5.9|0.5|0.5|1.6
Rice beverage, unfortified|9|0|0.6|1
Rice beverage, fluid, added calcium|12.7|0|0.3|1
Soy beverage, regular fat (~3%), unfortified|4.6|0.6|3.7|2.7
Soy beverage, regular fat (~3%), added Ca|4.6|0.6|3.7|2.7
Soy beverage, regular fat (~3%), added Ca & vitamins A, B1, B2 & B12|4.6|0.6|4.4|2.7
Soy beverage, reduced fat (~1%), added Ca & vitamins A, B1, B2 & B12|5.7|0.6|3|0.9
Textured vegetable protein/soy granules|15.2|17.5|51.5|1.2
Tofu (soy bean curd), firm, as purchased|0|1|12.8|8.3
Meat alternative, mycoprotein/fungus base, commercial, as purchased|2.5|5.6|13.8|4.1
Meat alternative, mycoprotein/fungus base, schnitzels/fish alternative/patty/sausages, as purchased|16|4.2|12.3|8.7
Meat alternative, mycoprotein/fungus base, cooked|3.4|7.7|18.8|5.6
Meat alternative, protein (soy/wheat/pea) base, as purchased|3.2|4.4|19.4|13.4
Meat alternative, protein (soy/wheat/pea) base, cooked|4|5.4|23.9|16.5
Meat alternative, protein (soy/wheat/pea) base, added vitamins & minerals, as purchased|3.2|4.4|19.4|13.4
Meat alternative, legume and/or vegetable base, as purchased|21.3|5.5|7.5|5.9
Meat alternative, legume and/or vegetable base, cooked|26.3|6.8|9.2|7.3
Sausage, vegetarian style, unfortified, raw|7.4|1.5|19|9.4
Sausage, vegetarian style, added Fe, Zn and vitamin B12, raw|7.4|1.5|19|9.4
Sausage, vegetarian style, fried, no added fat|7.8|1.6|20|9.9
Soup, broth style, with meat, instant dry mix|65.7|4.3|4.8|3.3
Soup, broth style, with meat & noodles, instant dry mix|64.1|2.2|10.8|6.3
Soup, cream variety, instant dry mix|59.1|6.5|5.3|13.2
Soup, vegetable, instant dry mix|58.3|3.3|6.7|2.1
Soup, vegetable & noodle, instant dry mix|65.5|3.9|8.7|6.3
Soup, chicken & noodle, cup of soup, prepared from instant dry mix with water|3.4|0.3|0.7|0.2
Butter, plain, salted|0.6|0|1.1|82.2
Butter, plain, reduced salt|0.6|0|1.1|82.6
Butter, plain, no added salt|0.6|0|1.1|82.2
Ghee, clarified butter|0|0|0.3|99.9
Dairy blend, butter & edible oil spread (~80% fat), sodium 600 mg/100 g|1|0|0.5|78.1
Dairy blend, butter & edible oil spread (~80% fat), reduced salt (sodium 400 mg/100 g)|1|0|0.5|78.1
Margarine spread, monounsaturated (>65% fat)|0|0|0.5|67.3
Margarine spread, monounsaturated (>65% fat), reduced salt (sodium 360 mg/100 g)|0|0|1|68.9
Margarine spread, monounsaturated, reduced fat (<65%)|0|0|1|48
Margarine spread, monounsaturated, reduced fat (~55%) & salt (sodium 380 mg/100 g)|0|0|1|51.5
Margarine spread, monounsaturated (~70% fat), reduced salt (sodium 350 mg/100 g), added phytosterols|0|0|0.7|75.9
Margarine spread, polyunsaturated (~70% fat)|0|0|0|69
Margarine spread, polyunsaturated (~70% fat), reduced salt (sodium 280 mg/100 g)|0|0|1|70.8
Oil, almond|0|0|0|100
Oil, avocado|0|0|0|100
Oil, canola|0|0|0|100
Oil, coconut|0|0|0|99.7
Oil, copha|0|0|0.2|99.9
Oil, cottonseed|0|0|0|100
Oil, grapeseed|0|0|0|100
Oil, hemp|0|0|0|99.9
Oil, linseed or flaxseed|0|0|0|100
Oil, macadamia|0|0|0|100
Oil, maize|0|0|0|100
Oil, mustard seed|0|0|0|100
Oil, olive|0|0|0|99.9
Oil, palm|0|0|0|100
Oil, peanut|0|0|0|100
Oil, rice bran|0|0|0|100
Oil, safflower|0|0|0|100
Oil, sesame|0|0|0|100
Oil, soybean|0|0|0|100
Oil, sunflower|0|0|0|100
Oil, blend of polyunsaturated vegetable oils|0|0|0|100
Oil, blend of monounsaturated vegetable oils|0|0|0|100
Oil, vegetable|0|0|0|100
Dripping, beef|0|0|0.2|100
Fat, solid, vegetable oil based|0|0|0.2|100
Egg, chicken, whole, raw|0.3|0|12.6|8.5
Egg, chicken, whole, poached|0.3|0|13.1|8.8
Egg, chicken, whole, hard-boiled|0.7|0|12.4|9.4
Egg, chicken, whole, fried, no fat added|0.3|0|14.3|9.6
Egg, chicken, whole, omega-3 polyunsaturate enriched, raw|0.3|0|12.4|9.2
Egg, chicken, whole, omega-3 polyunsaturate enriched, boiled|0.3|0|12.7|9.4
Egg, chicken, yolk, raw|0.2|0|15.6|28.2
Egg, chicken, yolk, hard-boiled|0.2|0|16.1|31.7
Egg, chicken, white (albumen), raw|0.4|0|11.2|0
Egg, chicken, white (albumen), hard-boiled|0.4|0|11.6|0
Egg, chicken, whole, scrambled, with regular fat cow's milk, no fat added|2.1|0|10.8|7.7
Apple, bonza, unpeeled, raw|11.5|3.2|0.3|0.3
Apple, fuji, unpeeled, raw|12.1|2.4|0.3|0.3
Apple, golden delicious, unpeeled, raw|11.6|2.4|0.3|0
Apple, green skin, unpeeled, raw|11.2|2.5|0.3|0
Apple, granny-smith, unpeeled, raw|10.7|2.5|0.3|0
Apple, jonathan, unpeeled, raw|11.9|2.3|0.3|0
Apple, pink lady, unpeeled, raw|12.3|3|0.2|0
Apple, red delicious, unpeeled, raw|13.5|2.3|0.3|0
Apple, royal gala, unpeeled, raw|12.1|2.1|0.4|0
Apple, red skin, peeled, raw|13|1.8|0.2|0
Apple, red skin, unpeeled, raw|12.7|2.5|0.2|0
Apple, dried|59.8|8.5|1.2|0
Apricot, raw|7.3|1.8|0.2|0.4
Apricot, dried|43|8.3|4.2|0.4
Apricot, canned in pear juice|8.2|1.8|0.8|0
Apricot, canned in pear juice, drained|7.8|3.1|1|0
Apricot, canned in pear juice, juice only|8.7|0|0.5|0
Banana, cavendish, peeled, raw|19.9|2.2|1.4|0.2
Banana, lady finger or sugar, peeled, raw|26.9|3|1|0
Banana, frozen|20.5|2.3|1.4|0.2
Banana chip|50.7|7.7|2.3|33.6
Currant, fresh|7.6|4.3|1.4|0.3
Blackberry, raw|7.4|4.5|1.2|0.3
Blackberry, purchased frozen|7.4|4.5|1.2|0.3
Blueberry, raw|10.4|3|0.5|0
Blueberry, purchased frozen|10.7|3|0.5|0
Cherry, raw|15.2|1.4|1.1|0
Cherry, black, canned in syrup|16|0.9|0.7|0
Cherry, black, canned in syrup, drained|16|1.7|0.9|0.1
Cherry, black, canned in syrup, syrup only|16|0|0.5|0
Cherry, pitted, frozen|15.6|1.4|1.1|0
Cranberry, raw|4.3|3.6|0.5|0.1
Cranberry, dried, sweetened|77.5|5.3|0.2|1.1
Currant, dried|69|4.8|2.7|0.5
Cumquat (kumquat), raw|9.4|6.5|1.9|0.9
Custard apple, African pride, peeled, raw|16.3|3.2|1.4|0.5
Date, dried|67.2|9.7|2|0.2
Feijoa, raw|8|3|0.6|0.4
Fig, fresh, unpeeled, raw|8.1|3.3|1.4|0.3
Fig, dried|54.5|14.3|3.6|0.7
Goji berry, dried|49.1|11.2|11.9|3.7
Grape, black muscatel (muscat), raw|18.8|2.7|1.1|0.1
Grape, black sultana, raw|14|3.3|1.2|0.1
Grape, cornichon, raw|12.7|2.5|1.3|0.1
Grape, green, raw|15.8|1|0.8|0
Grape, red globe, raw|17.1|2.2|0.8|0.1
Grape, waltham cross, raw|14.4|3.5|0.8|0.1
Grapefruit, pink flesh, peeled, raw|7.7|1.4|0.8|0
Grapefruit, yellow flesh, peeled, raw|6.5|2.2|0.9|0.2
Guava, Hawaiian, raw|3.5|5.4|0.7|0.5
Jackfruit, peeled, raw|16.7|3|2.2|0.3
Kiwifruit, green (hayward), peeled, raw|9.1|3|1.2|0.7
Kiwifruit, gold, peeled, raw|11.1|1.8|1.2|0.5
Lemon, peeled, raw|1.8|2|0.6|0.1
Lemon peel, raw|0|10.6|1.5|0.3
Lemon, preserved|1.3|3.2|0.7|0.1
Lime, peeled, raw|1.2|3.6|0.8|0.1
Lime, native, fruit|0.4|3.6|0.1|2.7
Loquat, peeled, raw|4.9|1.7|1|0.2
Lychee, peeled, raw|16.8|1.2|0.8|0
Lychee, peeled, frozen|17.3|1.3|0.9|0
Mandarin, peeled, raw|9.8|1.6|0.8|0.2
Mandarin, canned in syrup|13.5|0.6|0.4|0.1
Mandarin, canned in syrup, drained|13.8|1.1|0.5|0.2
Mandarin, canned in syrup, syrup only|13.2|0|0.2|0
Mango, peeled, raw|12.6|1.3|0.4|0
Mango, peeled, frozen|12.9|1.3|0.4|0
Mango, pulped, canned|14|1.2|0.4|0
Melon, honey dew, white skin, peeled, raw|7.1|1|0.7|0.3
Melon, honey dew, yellow skin, peeled, raw|4.4|0.9|0.8|0.3
Melon, honey dew, peeled, raw|6.9|0.8|0.4|0
Melon, rockmelon, peeled, raw|4.8|1|0.6|0
Melon, watermelon, peeled, raw|7.1|0.4|0.6|0
Mulberry, raw|4.3|2.2|2.2|0.2
Nectarine, white, unpeeled, raw|6.7|2.8|0.8|0
Nectarine, yellow, unpeeled, raw|7.9|1.6|0.8|0
Orange, navel, peeled, raw|8.2|2.2|1|0
Orange, valencia, peeled, raw|8.2|2.4|1|0
Orange, peeled, raw|8.2|2.3|1|0
Passionfruit, raw|6.9|11|2.8|1.4
Passionfruit, pulp, frozen|7.1|11.2|2.9|1.5
Passionfruit, pulp, canned|20.4|5.5|1.4|0.7
Pawpaw (papaya), peeled, raw|8.4|2.1|0.4|0
Papaya, red, peeled, raw|11.8|2.2|0.5|0
Papaya, yellow, peeled, raw|6.4|1.9|0.5|0
Peach, white, unpeeled, raw|7.5|1.6|0.7|0
Peach, yellow, unpeeled, raw|7.7|1.6|0.7|0
Peach, canned in pear juice|8.3|1.3|0.6|0
Peach, canned in pear juice, drained|8.2|2|0.8|0.1
Peach, canned in pear juice, juice only|8.5|0.3|0.4|0
Pear, Packham's triumph, unpeeled, raw|12.5|3|0.4|0
Pear, William Bartlett, unpeeled, raw|9|3.7|0.3|0
Pear, green skin, unpeeled, raw|10.8|3|0.4|0
Pear, brown skin, unpeeled, raw|9.6|3.8|0.4|0
Pear, canned in pear juice|8.7|1.7|0.5|0
Pear, canned in pear juice, drained|8.6|2.8|0.6|0
Pear, canned in pear juice, juice only|8.8|0.2|0.2|0
Pear, nashi, unpeeled, raw|9.6|2.1|0.2|0
Persimmon, peeled, raw|16|2.7|0.6|0.1
Pineapple, peeled, raw|8.6|1.3|0.5|0
Pineapple, peeled, frozen|8.8|1.3|0.5|0
Pineapple, canned in pineapple juice|12|0.8|0.5|0.6
Pineapple, canned in juice, drained|10.9|1.1|0|0
Pineapple, canned in pineapple juice, juice only|10.6|0|0.3|0
Pineapple, canned in syrup|19.8|0.7|0.3|0.6
Pineapple, canned in syrup, drained|17.5|1.2|0|0
Pineapple, canned in syrup, syrup only|22|0|0.3|0
Plum, unpeeled, raw|9|2|0.6|0
Plum, dark, canned in syrup|21.3|1|0.4|0
Plum, dark, canned in syrup, drained|21.3|2|0.5|0.1
Plum, dark, canned in syrup, syrup only|21.3|0|0.3|0
Plum, davidson (native), flesh|14.3|2|1|0.2
Plum, salted|34.4|9.3|3.7|0
Pomegranate, peeled, raw|13.5|6.4|1.9|0.2
Prickly pear, peeled, raw|8.8|4.6|0.4|0.3
Prune (dried plum)|32.3|7.7|2.6|0.4
Quandong, fruit, flesh|8.1|4.2|2.5|0
Quince, peeled, raw|11|5.2|0.5|0.2
Raisin|74.4|2.2|2.1|0.9
Rambutan, raw|15.7|2.8|1|0.4
Raspberry, raw|6.9|4.8|1.1|0.3
Raspberry, purchased frozen|6.9|4.8|1.1|0.3
Raspberry, canned in syrup|17|2.5|0.6|0.1
Raspberry, canned in syrup, drained|16.2|6|1|0.2
Raspberry, canned in syrup, syrup only|17.5|0|0.2|0
Rhubarb, stalk, raw|1.7|2.2|1.5|0.2
Rhubarb, stewed, sugar sweetened, no added fat|13.3|2.1|1.4|0.2
Sultana|75.8|3.3|2.6|0.3
Strawberry, raw|5.6|2.1|0.7|0.2
Strawberry, purchased frozen|5.6|2.1|0.7|0.2
Strawberry, canned in syrup|18.8|1.5|0.4|0.2
Strawberry, canned in syrup, drained|18.7|3.5|0.9|0.1
Strawberry, canned in syrup, syrup only|18.9|0|0.1|0.2
Tamarillo, peeled, raw|3.4|4.7|2|0.1
Tamarind, paste, pure|57.4|5.1|2.8|0.6
Tangelo, peeled, raw|7.8|2|0.6|0.1
Tangerine or tangor, peeled, raw|8.1|1.5|1|0.2
Wax jambu, raw|4.5|1.9|0.7|0.2
Fruit salad, canned in fruit juice|9.8|1.4|0.7|0.1
Fruit salad, canned in fruit juice, drained|9.5|2.3|0.8|0.2
Fruit salad, canned in fruit juice, juice only|10.2|0.2|0.4|0
Mixed berry, frozen|9.6|3.3|0.6|0.1
Mixed dried fruit|73.6|3.3|2.2|0.7
Baked beans, canned in tomato sauce|13|4.8|4.8|0.3
Baked beans, canned in tomato sauce, reduced salt|13|4.8|4.8|0.3
Bean, broad, dried|37|18.6|24.3|2.1
Bean, broad, fresh, raw|2.2|7.1|6.9|0.5
Bean, broad, fresh, boiled, drained|2.4|7.6|7.4|0.5
Bean, butter, fresh, raw|2.3|2.3|2.3|0.2
Bean, butter, fresh, boiled, drained|2.5|2.5|2.5|0.2
Bean, haricot, dried|34.8|18.5|21.9|2.2
Bean, haricot, dried, boiled, drained|12.6|8.8|8.2|0.7
Bean, lima, dried|40.6|17.7|21.4|1.7
Bean, lima, dried, boiled, drained|10.2|5.3|6.4|0.3
Bean, mung, whole, dried, uncooked|41.9|13.7|24.5|2
Bean, red kidney, dried|35.6|21.5|22.5|1.8
Bean, red kidney, dried, boiled, drained|9.1|7.2|7.9|0.5
Bean, red kidney, fresh, raw|14.7|9.1|12.8|0.4
Bean, red kidney, fresh, boiled, drained|15.8|9.8|13.8|0.4
Bean, red kidney, canned, drained|14|6.5|6.6|0.6
Bean, soya (soy), dried|12.5|20.1|31.3|20.2
Bean, soya (soy), dried, boiled, drained|1.4|7.2|13.5|7.7
Chickpea, dried|38.5|20.8|19.7|5.5
Chickpea, dried, boiled, drained|14|7.6|7.2|2
Chickpea, canned, drained|13.3|5.7|6.3|2.1
Dip, hummus (hommus), commercial|9.4|3.9|6.9|19.5
Lentil, French, hulled, dry|38.8|15|28.3|2
Lentil, green, hulled, dry|40.9|14.6|25.3|1.7
Lentil, red, hulled, dry|45.7|13.7|23|1.9
Lentil, hulled, dry|41.8|14.4|25.5|1.9
Lentil, dried, boiled, drained|11.9|4.1|7.3|0.5
Lupin, dehulled, splits, uncooked|10|27.8|37.5|7.9
Lupin, dehulled, flakes, uncooked|10|27|36.9|7.7
Lupin, whole, uncooked|7.4|41.5|28.6|6.5
Pea, split, dried|50.5|10.1|23|2
Pea, split, dried, boiled, drained|9.1|8.3|6.6|0.4
Pea, green, whole, dried|40.9|16.8|22|2.1
Pea, yellow, whole, dried|43.6|17.7|23.3|2.3
Beef, all cuts, separable fat, raw|0|0|12.1|61.4
Beef, all cuts, separable fat, cooked, no added fat|0|0|15.3|55.6
Beef, casserole meat, boneless or bone-in, shin, lean, raw|0|0|21.9|0.6
Beef, casserole meat, boneless or bone-in, shin, lean, casseroled, no added fat|0|0.5|33.8|2.8
Beef, casserole meat, boneless or bone-in, shin, untrimmed, raw|0|0|21.3|4.2
Beef, casserole meat, boneless or bone-in, shin, untrimmed, casseroled, no added fat|0|0.4|30.6|11.8
Beef, casserole meat, boneless, chuck, lean, raw|0|0|23|3.4
Beef, casserole meat, boneless, chuck, lean, casseroled, no added fat|0|0|33|10.6
Beef, casserole meat, boneless, chuck, untrimmed, raw|0|0|21.7|10.4
Beef, casserole meat, boneless, chuck, untrimmed, casseroled, no added fat|0|0|31.2|15.1
Beef, diced, lean, raw|0|0|27.9|2.7
Beef, diced, lean, dry-fried, no added fat|0|0|32.1|2.9
Beef, diced, lean, casseroled, no added fat|0|0|36.5|3.5
Beef, diced, untrimmed, raw|0|0|27.6|3.9
Beef, diced, untrimmed, dry-fried, no added fat|0|0|31.8|3.7
Beef, diced, untrimmed, casseroled, no added fat|0|0|36.2|4.3
Beef, stir-fry strips, lean, raw|0|0|27.2|2
Beef, stir-fry strips, lean, dry-fried, no added fat|0|0|30.9|3.2
Beef, strips, lean, casseroled, no added fat|0|0|37.5|2.8
Beef, stir-fry strips, untrimmed, raw|0|0|27|2.7
Beef, stir-fry strips, untrimmed, dry-fried, no added fat|0|0|30.8|3.6
Beef, strips, untrimmed, casseroled, no added fat|0|0|37.3|3.2
Beef, mince, lower fat, raw|0|0|22.9|4.1
Beef, mince, lower fat, stir-fried, no added fat|0|0|32.3|8.1
Beef, mince, regular fat, raw|0|0|22.4|8.9
Beef, mince, regular fat, stir-fried, no added fat|0|0|29.5|16.4
Beef, mince, higher fat, raw|0|0|22.5|10.4
Beef, mince, higher fat, stir-fried, no added fat|0|0|27.6|13.5
Beef, rump steak, lean, raw|0|0|20.4|2.8
Beef, rump steak, lean, grilled, no added fat|0|0|32|4.5
Beef, rump steak, untrimmed, raw|0|0|20|5.6
Beef, rump steak, untrimmed, grilled, no added fat|0|0|29.9|10.8
Beef, sirloin steak, lean, raw|0|0|24.1|1.9
Beef, sirloin steak, lean, grilled, no added fat|0|0|30.5|3.8
Beef, sirloin steak, untrimmed, raw|0|0|23.4|5.4
Beef, sirloin steak, untrimmed, grilled, no added fat|0|0|27.3|14.7
Beef, steak, boneless, fillet or tenderloin, lean, raw|0|0|22.2|5.2
Beef, steak, boneless, fillet or tenderloin, lean, grilled, no added fat|0|0|31.9|5.5
Beef, steak, boneless, fillet or tenderloin, untrimmed, raw|0|0|21.4|9.3
Beef, steak, boneless, fillet or tenderloin, untrimmed, grilled, no added fat|0|0|30.8|8.8
Beef, steak, boneless or bone-in, blade, lean, raw|0|0|22.2|2.6
Beef, steak, boneless or bone-in, blade, lean, grilled, no added fat|0|0|31.9|2.9
Beef, steak, boneless or bone-in, blade, untrimmed, raw|0|0|21|9.5
Beef, steak, with bone, blade, untrimmed, grilled, no added fat|0|0|30.5|7.4
Beef, steak, boneless, round, lean, raw|0|0|21|1.7
Beef, steak, boneless, round, lean, grilled, no added fat|0|0|31.8|2
Beef, steak, boneless, round, untrimmed, raw|0|0|20.4|6
Beef, steak, boneless, round, untrimmed, grilled, no added fat|0|0|31|4.6
Beef, steak, boneless, scotch fillet, lean, raw|0|0|23.8|2.8
Beef, steak, boneless, scotch fillet, lean, grilled, no added fat|0|0|31.9|4.5
Beef, steak, boneless, scotch fillet, untrimmed, raw|0|0|22.4|10
Beef, steak, boneless, scotch fillet, untrimmed, grilled, no added fat|0|0|29.4|12.3
Beef, steak, with bone, T-bone, lean, raw|0|0|24.4|2
Beef, steak, with bone, T-bone, lean, grilled, no added fat|0|0|29.2|3.8
Beef, steak, with bone, T-bone, untrimmed, raw|0|0|21.3|16.9
Beef, steak, with bone, T-bone, untrimmed, grilled, no added fat|0|0|26.3|14.5
Beef, silverside roast, lean, raw|0|0|24.1|2.3
Beef, silverside roast, lean, roasted, no added fat|0|0|26.9|5.6
Beef, silverside roast, untrimmed, raw|0|0|22.8|8.7
Beef, silverside roast, untrimmed, roasted, no added fat|0|0|26|9.6
Beef, topside roast, lean, raw|0|0|22.3|3
Beef, topside roast, lean, roasted, no added fat|0|0|32.7|2.8
Beef, topside roast, untrimmed, raw|0|0|21.3|8.6
Beef, topside roast, untrimmed, roasted, no added fat|0|0|31.3|7.1
Meatball or rissole, beef mince, commercial, raw|2|1|15.5|16.3
Meatball or rissole, beef, commercial, cooked|2.7|1.4|20.9|22
Veal, all cuts, separable fat, raw|0|0|19.4|29
Veal, all cuts, separable fat, cooked|0|0|20.7|33
Veal, cutlet, with bone, lean, raw|0|0|24.6|1.1
Veal, cutlet, with bone, lean, grilled, no added fat|0|0|28.1|2
Veal, cutlet, with bone, untrimmed, raw|0|0|24.2|3
Veal, cutlet, with bone, untrimmed, grilled, no added fat|0|0|27.5|4.4
Veal, diced, lean, raw|0|0|23.1|1.5
Veal, diced, lean, stir-fried, no added fat|0|0|31.9|3
Veal, diced, untrimmed, raw|0|0|23|2
Veal, diced, untrimmed, stir-fried, no added fat|0|0|31.6|3.8
Veal, stir-fry strips, lean, raw|0|0|27.2|2
Veal, stir-fry strips, lean, stir-fried, no added fat|0|0|30|2.8
Veal, stir-fry strips, untrimmed, raw|0|0|27.1|2.5
Veal, stir-fry strips, untrimmed, stir-fried, no added fat|0|0|29.9|3.1
Veal, steak, boneless, leg, lean, raw|0|0|24.4|1.5
Veal, steak, boneless, leg, lean, grilled, no added fat|0|0|34.4|1.9
Veal, steak, boneless, leg, untrimmed, raw|0|0|24.3|1.8
Veal, steak, boneless, leg, untrimmed, grilled, no added fat|0|0|34.1|2.6
Lamb, all cuts, separable fat, raw|0|0|10.8|57.6
Lamb, all cuts, separable fat, cooked|0|0|16.2|53.4
Lamb, chop, with bone, chump, lean, raw|0|0|22.5|4.3
Lamb, chop, with bone, chump, lean, grilled, no added fat|0|0|34.1|10.2
Lamb, chop, with bone, chump, untrimmed, raw|0|0|19.6|17.5
Lamb, chop, with bone, chump, untrimmed, grilled, no added fat|0|0|30|20
Lamb, chop, with bone, forequarter, lean, raw|0|0|20.5|5.7
Lamb, chop, with bone, forequarter, lean, grilled, no added fat|0|0|28|11.4
Lamb, chop, with bone, forequarter, untrimmed, raw|0|0|17.4|22.4
Lamb, chop, with bone, forequarter, grilled, no added fat|0|0|25.6|19.9
Lamb, loin chop, lean, raw|0|0|28.6|5
Lamb, loin chop, lean, grilled, no added fat|0|0|29.8|9.1
Lamb, loin chop, untrimmed, raw|0|0|22|24.4
Lamb, loin chop, untrimmed, grilled, no added fat|0|0|26.8|18.9
Lamb, cutlet or frenched cutlet, with bone, lean, raw|0|0|20.9|6.2
Lamb, cutlet or frenched cutlet, with bone, lean, grilled, no added fat|0|0|30.5|8.6
Lamb, cutlet or frenched cutlet, with bone, untrimmed, raw|0|0|18.6|17.8
Lamb, cutlet or frenched cutlet, with bone, untrimmed, grilled, no added fat|0|0|27.2|18.9
Lamb, diced, lean, raw|0|0|21.3|5.2
Lamb, diced, lean, stir-fried, no added fat|0|0|31.3|6.5
Lamb, diced, lean, casseroled, no added fat|0|0|32.2|6.2
Lamb, diced, untrimmed, raw|0|0|21.1|6.2
Lamb, diced, untrimmed, stir-fried, no added fat|0|0|31|7.6
Lamb, diced, untrimmed, casseroled, no added fat|0|0|31.8|7.2
Lamb, stir-fry strips, lean, raw|0|0|21.9|4.2
Lamb, stir-fry strips, lean, stir-fried, no added fat|0|0|28.1|7.7
Lamb, stir-fry strips, lean, casseroled, no added fat|0|0|32.2|6.2
Lamb, stir-fry strips, untrimmed, raw|0|0|21.6|5.7
Lamb, stir-fry strips, untrimmed, stir-fried, no added fat|0|0|28|8.3
Lamb, stir-fry strips, untrimmed, casseroled, no added fat|0|0|32|6.8
Lamb, mince, raw|0|0|20.4|6.9
Lamb, mince, stir-fried, no added fat|0|0|24.4|8.5
Lamb, leg roast, lean, raw|0|0|21.1|3.2
Lamb, leg roast, lean, roasted, no added fat|0|0|30.6|6
Lamb, leg roast, untrimmed, raw|0|0|19.4|12.3
Lamb, leg roast, untrimmed, roasted, no added fat|0|0|28.5|12.8
Lamb, mini roast, lean, roasted, no added fat|0|0|27.8|5.6
Lamb, mini roast, untrimmed, roasted, no added fat|0|0|26.5|11
Lamb, roasting piece, shoulder, lean, raw|0|0|18.2|4.3
Lamb, roasting piece, shoulder, lean, roasted, no added fat|0|0|29.6|5.4
Lamb, roasting piece, shoulder, untrimmed, raw|0|0|16.5|16.6
Lamb, roasting piece, shoulder, untrimmed, roasted, no added fat|0|0|26.2|17.5
Lamb, eye of loin, lean, raw|0|0|20.8|3.9
Lamb, eye of loin, lean, microwaved, no added fat|0|0|26.8|5.6
Lamb, eye of loin, untrimmed, raw|0|0|20.6|5.3
Lamb, eye of loin, untrimmed, microwaved, no added fat|0|0|26.6|6.5
Lamb, fillet, lean, raw|0|0|19.8|4
Lamb, fillet, lean, grilled, no added fat|0|0|27.5|5.5
Lamb, fillet, untrimmed, raw|0|0|19.3|6.9
Lamb, fillet, untrimmed, grilled, no added fat|0|0|27.2|6.6
Lamb, steak, boneless, lean, raw|0|0|21.9|4.7
Lamb, steak, boneless, lean, grilled, no added fat|0|0|30.8|6.6
Lamb, steak, boneless, untrimmed, raw|0|0|20.4|11.8
Lamb, steak, boneless, untrimmed, grilled, no added fat|0|0|29.2|11.9
Lamb, with bone, shin, lean, raw|0|0|23.9|1.9
Lamb, with bone, shin, lean, casseroled, no added fat|0|0|34.6|4.5
Lamb, with bone, shin, untrimmed, raw|0|0|21.7|11.2
Lamb, with bone, shin, untrimmed, casseroled, no added fat|0|0|32|11.4
Mutton, all cuts, separable fat, raw|0|0|8.2|64.4
Mutton, all cuts, separable fat, cooked|0|0|10.7|67.9
Mutton, boneless dice or strips, shoulder, lean, raw|0|0|21.3|4.2
Mutton, boneless dice or strips, shoulder, lean, casseroled, no added fat|0|0|36.4|11.4
Mutton, boneless dice or strips, shoulder, semi-trimmed, casseroled, no added fat|0|0|35.2|13.9
Mutton, boneless dice or strips, shoulder, untrimmed, raw|0|0|20|10.2
Mutton, boneless dice or strips, shoulder, untrimmed, casseroled, no added fat|0|0|34.9|14.7
Mutton, roasting piece, with bone, leg, lean, raw|0|0|21.6|3.8
Mutton, roasting piece, with bone, leg, lean, roasted, no added fat|0|0|29.8|7.7
Mutton, roasting piece, with bone, leg, untrimmed, raw|0|0|19.6|12.9
Mutton, roasting piece, with bone, leg, untrimmed, roasted, no added fat|0|0|26.8|17.2
Buffalo, riverine, cube roll, raw|0|0|23.9|6.6
Buffalo, riverine, topside, raw|0|0|24.4|2.2
Buffalo, swamp, cube roll, raw|0|0|24.4|2.5
Buffalo, swamp, topside, raw|0|0|24.6|0.8
Camel, cube roll, raw|0|0|20.3|7.7
Camel, rump, raw|0|0|21.4|3
Camel, steak, as purchased, raw|0|0|20.8|4.3
Camel, steak, as purchased, fried, no added fat|0|0|32|6.7
Goat, all cuts, separable fat, raw|0|0|12.2|44.6
Goat, all cuts, separable fat, baked, no added fat|0|0|10|64.5
Goat, forequarter, lean, raw|0|0|21.6|1.8
Goat, forequarter, lean, baked, no added fat|0|0|30.8|4.2
Goat, leg, lean, raw|0|0|22.2|2.1
Goat, leg, lean, baked, no added fat|0|0|33.8|3.2
Goat, loin, lean, raw|0|0|22.2|1.4
Goat, loin, lean, baked, no added fat|0|0|29.2|3.1
Goat, meat, all cuts, lean, raw|0|0|22|1.8
Goat, meat, all cuts, lean, baked, no added fat|0|0|31.3|3.5
Goat, meat, all cuts, lean, casseroled, no added fat|0|0|43.6|3.5
Goat, meat, all cuts, untrimmed, raw|0|0|21.2|5.6
Goat, meat, all cuts, untrimmed, baked, no added fat|0|0|29.2|9.5
Goat, meat, all cuts, untrimmed, casseroled, no added fat|0|0|37.8|10
Rabbit, farmed, whole, raw|0|0|23.2|2.1
Rabbit, flesh, casseroled, no added fat|0.2|0|29.3|5.7
Venison, leg medallion, lean, raw|0|0|23.5|1.5
Venison, leg medallion, lean, baked, roasted, fried, stir-fried, grilled or BBQ'd, no added fat|0|0|28.7|1.9
Venison, mince, premium, raw|0|0|21|2.9
Venison, mince, premium, baked, roasted, fried, stir-fried, grilled or BBQ'd, no added fat|0|0|29.8|3.7
Pork, belly, rind removed, raw|0|0|18.6|26.2
Pork, belly, rind removed, baked, no added fat|0|0|27.4|30.8
Pork, crackling, roasted, salted|0|0|8.9|71.2
Pork, diced, untrimmed, raw|0|0|22.8|3.1
Pork, diced, untrimmed, fried, no added fat|0|0|31.3|3.1
Pork, strips, untrimmed, raw|0|0|22.4|2.3
Pork, strips, untrimmed, fried, no added fat|0|0|31.2|2.5
Pork, forequarter (chop, roast, neck), separable fat, raw|0|0|9.6|50.8
Pork, forequarter, separable fat, BBQ'd, no added fat|0|0|11.5|53.6
Pork, forequarter chop, lean, raw|0|0|19.5|3.9
Pork, forequarter chop, lean, BBQ'd, no added fat|0|0|26.3|6.3
Pork, forequarter chop, untrimmed, raw|0|0|17.5|13.2
Pork, forequarter chop, untrimmed, BBQ'd, no added fat|0|0|23.3|16
Pork, loin chop, separable fat, raw|0|0|7.9|69.2
Pork, loin chop, separable fat, BBQ'd, no added fat|0|0|8.8|70.7
Pork, loin chop, lean, raw|0|0|23.2|1.8
Pork, loin chop, lean, BBQ'd, no added fat|0|0|30.6|4.5
Pork, loin chop, untrimmed, raw|0|0|21.5|9.2
Pork, loin chop, untrimmed, BBQ'd, no added fat|0|0|28|12.2
Pork, loin cutlet, untrimmed, raw|0|0|21|13.1
Pork, loin cutlet, untrimmed, fried, no added fat|0|0|30.9|19.3
Pork, mince, untrimmed, raw|0|0|20.9|10.6
Pork, mince, as purchased, fried, no added fat|0|0|25.9|13.2
Pork, butterfly steak, lean, raw|0|0|24.1|1.6
Pork, butterfly steak, lean, grilled, no added fat|0|0|32.5|2.8
Pork, butterfly steak, untrimmed, raw|0|0|22.7|7
Pork, butterfly steak, untrimmed, grilled, no added fat|0|0|30.6|8.2
Pork, medallion or loin steak, separable fat, raw|0|0|6.3|68.5
Pork, medallion or loin steak, separable fat, fried, no added fat|0|0|14.9|55.5
Pork, medallion or loin steak, lean, raw|0|0|24.1|1.6
Pork, medallion or loin steak, lean, fried, no added fat|0|0|29.5|3.5
Pork, medallion or loin steak, untrimmed, raw|0|0|22.9|6.1
Pork, medallion or loin steak, untrimmed, fried, no added fat|0|0|33.2|8.8
Pork, leg steak (rump), separable fat, raw|0|0|8.6|66.4
Pork, leg steak (rump), separable fat, fried, no added fat|0|0|12|53.7
Pork, rump steak, lean, raw|0|0|25.3|2.1
Pork, rump steak, lean, fried, no added fat|0|0|28.6|3.8
Pork, rump steak, untrimmed, raw|0|0|24.8|4
Pork, rump steak, untrimmed, fried, no added fat|0|0|36.5|5.8
Pork, fillet, lean, raw|0|0|23.1|1.1
Pork, fillet, fully-trimmed, baked, no added fat|0|0|28.5|2.2
Pork, forequarter shoulder roast, lean, raw|0|0|19.6|3.1
Pork, forequarter shoulder roast, lean, BBQ'd, no added fat|0|0|24.8|6.4
Pork, forequarter shoulder roast, untrimmed, raw|0|0|17.6|12.5
Pork, forequarter shoulder roast, untrimmed, BBQ'd, no added fat|0|0|22.6|14
Pork, leg roast, untrimmed, raw|0|0|21.6|8.6
Pork, leg roast, untrimmed, baked, no added fat|0|0|31.2|10.7
Pork, loin roast, separable fat, raw|0|0|5.2|77.1
Pork, loin roast, separable fat, baked, no added fat|0|0|8.8|67
Pork, loin roast, lean, raw|0|0|23.3|2.2
Pork, loin roast, lean, baked, no added fat|0|0|31.9|8.6
Pork, loin roast, untrimmed, raw|0|0|19.9|16.4
Pork, loin roast, untrimmed, baked, no added fat|0|0|28.8|16.3
Pork, round mini roast, separable fat, raw|0|0|11.3|44.6
Pork, round mini roast, separable fat, baked, no added fat|0|0|16.3|45.5
Pork, round mini roast, lean, raw|0|0|23.7|1.1
Pork, round mini roast, lean, baked, no added fat|0|0|34.7|2.5
Pork, scotch roast, separable fat, raw|0|0|10.7|52.5
Pork, scotch roast, separable fat, baked, no added fat|0|0|12.2|50.5
Pork, scotch roast, lean, raw|0|0|20.6|7.6
Pork, scotch roast, lean, baked, no added fat|0|0|30.6|12
Pork, scotch roast, untrimmed, raw|0|0|18.9|15.4
Pork, scotch roast, untrimmed, baked, no added fat|0|0|29.6|14.1
Pork, spare ribs, untrimmed, raw|0|0|18.1|24
Pork, spare ribs, untrimmed, baked, no added fat|0|0|27.4|32.4
Pork, spare ribs, marinated, baked, roasted, fried, grilled or BBQ'd, fat not further defined|5.1|0.1|23.4|27
Chicken, separable fat, composite, raw|0|0|5.1|62.3
Chicken, separable fat, composite, baked or roasted, no added fat|0|0|9.8|46.6
Chicken, separable fat, composite, cooked, no added fat|0|0|6.2|75.5
Chicken, skin, composite, raw|0|0|12.7|35.1
Chicken, skin, composite, baked, no added fat|0|0|22.3|38.9
Chicken, skin, composite, casseroled, no added fat|0|0|18|38.8
Chicken, barbecued, with skin|0|1.1|27.5|8.9
Chicken, breast, lean flesh, raw|0|0|22.5|0.8
Chicken, breast, lean flesh, baked, no added fat|0|0|29|3.9
Chicken, breast, lean flesh, grilled, no added fat|0|0|29.8|2.5
Chicken, breast, lean flesh, fried, no added fat|0.7|0|34.5|1.8
Chicken, breast, lean flesh, casseroled, no added fat|0|0|27|3.7
Chicken, breast, lean flesh, skin & fat, raw|0|0|20.2|8.7
Chicken, breast, lean flesh, skin & fat, baked, no added fat|0|0|27.8|9.1
Chicken, drumstick, lean flesh, raw|0|0|18.5|4.8
Chicken, drumstick, lean flesh, baked, no added fat|0.4|0|29.9|8.5
Chicken, drumstick, lean flesh, casseroled, no added fat|0|0|28.6|6.6
Chicken, drumstick, lean flesh, skin & fat, raw|0|0|17.6|9.3
Chicken, drumstick, lean flesh, skin & fat, baked, no added fat|0.3|0|28.5|13.6
Chicken, drumstick, lean flesh, skin & fat, casseroled, no added fat|0|0|26.9|11.6
Chicken, mince, raw|0|0|21.4|1.1
Chicken, mince, fried, no added fat|0|0|28.1|1.4
Chicken, thigh, lean flesh, raw|0|0|19.1|3
Chicken, thigh, lean flesh, baked, no added fat|0|0|24.2|8.7
Chicken, thigh, lean flesh, casseroled, no added fat|0|0|26.3|9.8
Chicken, thigh, lean flesh, fried, no added fat|0|0|27.6|5.2
Chicken, thigh, lean flesh, skin & fat, raw|0|0|16|16.9
Chicken, thigh, lean flesh, skin & fat, baked, no added fat|0|0|23.1|16.1
Chicken, thigh, lean flesh, skin & fat, casseroled, no added fat|0|0|24.1|17.1
Chicken, wing, lean flesh, raw|0|0|18.7|4.1
Chicken, wing, lean flesh, baked, no added fat|0|0|30.9|7.7
Chicken, wing, lean flesh, casseroled, no added fat|0|0|27.9|8.8
Chicken, wing, lean flesh, skin & fat, raw|0|0|16.1|17.4
Chicken, wing, lean flesh, skin & fat, baked, no added fat|0|0|28.2|17.2
Chicken, wing, lean flesh, skin & fat, casseroled, no added fat|0|0|24.9|17.9
Chicken, nugget, purchased frozen, baked, no added fat|17.8|1.6|14.1|15.2
Chicken, nugget, purchased from independent takeaway & fast food chain, fried, undefined oil|16.8|1.1|13.1|15.8
Chicken, nugget, purchased from fast food chain, grilled|19.9|1.3|14.8|5.9
Chicken piece, flesh, skin & fat, coated, fast food outlet, fried|9.5|1|23.2|14.4
Chicken, schnitzel, baked without fat|13.1|1.7|16.5|9.6
Turkey, breast, lean flesh, raw|0|0|21.6|3.3
Turkey, breast, lean flesh, baked, no added fat|0|0|29.4|4
Turkey, breast, lean flesh, skin & fat, raw|0|0|20.4|8.5
Turkey, breast, lean flesh, skin & fat, baked, no added fat|0|0|27.7|9.2
Turkey, hindquarter, lean flesh, raw|0|0|18.4|5.3
Turkey, hindquarter, lean flesh, baked, no added fat|0|0|26.8|7
Turkey, hindquarter, lean flesh, skin & fat, raw|0|0|18.6|12.6
Turkey, hindquarter, lean flesh, skin & fat, baked, no added fat|0|0|25.6|12.9
Duck, skin & fat, raw|0|0|9.5|59.2
Duck, skin & fat, baked, no added fat|0|0|13.4|48.3
Duck, breast, lean flesh, raw|0|0|21.2|0.6
Duck, breast, lean, fried, no added fat|0|0|28|0.8
Duck, lean flesh, raw|0|0|17.8|5.5
Duck, lean flesh, baked, no added fat|0|0|28.3|0.8
Duck, lean flesh, skin & fat, raw|0|0|13|36.9
Duck, lean flesh, skin & fat, baked, no added fat|0|0|17.3|39.4
Quail, lean flesh & skin, raw|0|0|18.5|11
Quail, lean flesh & skin, baked, no added fat|0|0|27.7|9.6
Emu, fan fillet, raw|0|0|24.7|1.8
Emu, steak, as purchased, raw|0|0|23.4|2.5
Emu, steak, as purchased, fried, no added fat|0|0|30.8|3.3
Kangaroo, loin fillet, raw|0|0|21.4|0.9
Kangaroo, loin fillet, grilled, no added fat|0|0|30.1|1.2
Kangaroo, rump, raw|0|0|20.3|0.8
Kangaroo, rump, baked, no added fat|0|0|33.9|2.5
Kangaroo, steak, as purchased, raw|0.4|0|22.5|1
Kangaroo, steak, as purchased, fried, no added fat|0.6|0|34.6|1.5
Kangaroo, tail, raw|0|0|20|1.1
Kangaroo, tail, cooked, no added fat|0|0|24.4|1.3
Ostrich, fan fillet, raw|0|0|23.4|0.7
Ostrich, moon steak, raw|0|0|23.7|1.4
Pigeon (squab), whole, raw|0|0|16.2|25.5
Pigeon (squab), meat, cooked|0|0|22.3|35.2
Crocodile, back leg, raw|0|0|22|2.2
Crocodile, tail fillet, raw|0|0|22.5|1.6
Crocodile, steak, as purchased, raw|0|0|20.6|1.5
Crocodile, steak, as purchased, fried, no added fat|0|0|34.4|2.5
Chicken, liver, raw|0|0|16.9|4.8
Chicken, liver, fried, added butter|0|0|20.2|10.9
Lamb, liver, grilled, no added fat|2.6|0|21.5|10.2
""",
    """
Bacon, middle rasher, untrimmed, raw|0.2|0|15.4|28.2
Bacon, middle rasher, untrimmed, baked, roasted, fried, grilled or BBQ'd, no added fat|0.3|0|20.6|37.6
Bacon, middle rasher, separable lean, fried, no added fat|0.8|0|31.2|8.5
Bacon, middle rasher, separable fat, fried, no added fat|0.4|0|11.6|81.1
Bacon, shortcut, as purchased without rind, fried, no added fat|0.9|0|30.4|13.9
Ham, leg, lean|2|1.8|17|2.5
Prosciutto|0.3|0|30.8|18.8
Frankfurt, cooked|3.4|1.8|14.3|17
Sausage, beef, raw|2.9|0|14.5|18.7
Sausage, beef, fried, no added fat|4|0.4|16.4|18.4
Sausage, beef, grilled, no added fat|3.8|0.2|17.2|14.9
Sausage, chicken, plain, fried, no added fat|4.9|0.6|18.2|12
Sausage, chicken, flavoured, fried, no added fat|4.9|0.6|18.2|12
Sausage, lamb, plain, fried, no added fat|4.9|0.4|15.6|16.1
Sausage, lamb, flavoured, fried, no added fat|4.9|0.4|15.6|16.1
Sausage, pork, fried, no added fat|4.5|1.4|16.5|19.4
Sausage, chorizo, uncooked|1.2|0.5|20.1|22.1
Sausage, chorizo, fried, no added fat|1.3|0.5|21.2|23.3
Salami, danish|1.2|0|19.6|40.2
Salami, hungarian|0.9|0|21.6|37.4
Salami, mettwurst|0.8|0|21.9|37.5
Salami, milano|2.2|0|21.7|36.9
Salami, pepperoni|1.6|0|23.5|36.1
Salami, all varieties, raw|0.4|0.5|21.9|28.3
Chicken, processed luncheon meat, low or reduced fat|4.4|0.2|20|1.2
Beef, corned, canned|0.3|0.7|21.5|15.7
Braised steak & onions, canned|4.5|0.8|7.2|1.6
Pie, steak & kidney, canned|13.6|0.9|10|7.7
Sausages & vegetables, canned|7.8|0.9|4|2.1
Spam, canned|1.9|0|14.2|26.1
Steak & vegetables, canned|6.2|1.2|4.4|1.9
Coconut, fresh, mature fruit, flesh|3.3|8.1|3.8|32.8
Coconut, fresh, young or immature, flesh|7|0.5|1.6|5.6
Coconut, fresh, mature, water or juice|4.7|0|0.5|0.1
Coconut, fresh, young or immature, water or juice|6.6|0|0.2|0
Water, coconut, commercial|3.2|0|0.2|0
Coconut, grated & desiccated|7.3|15.4|6.9|65.4
Coconut, cream, regular fat|1.4|0|1.6|23.2
Coconut, milk, canned, regular fat|1.7|0|1.4|15.4
Coconut, milk, canned, reduced fat|0.9|0|0.6|7.1
Nut, almond, with skin, raw, unsalted|5.4|11|19.7|50.5
Nut, almond, with skin, roasted, unsalted|5.4|11|19.7|52.5
Nut, almond meal|5.4|11|19.7|50.5
Nut, brazil, raw or blanched, unsalted|2.4|8.5|14.4|68.5
Nut, cashew, raw, unsalted|22.9|5.9|17|49.2
Nut, cashew, roasted, unsalted|26.8|3.4|17|47.7
Nut, cashew, roasted, salted|26.7|3|17|48.5
Nut, chestnut, raw, unsalted|32.1|14.9|3.4|0.2
Nut, chestnut, roasted, unsalted|35.7|8|3.4|1.2
Nut, hazelnut, raw, unsalted|5.1|10.4|14.8|61.4
Nut, macadamia, raw, unsalted|4.6|7.9|6.5|76.7
Nut, peanut, with skin, raw, unsalted|8.9|8.2|24.7|47.1
Nut, peanut, with skin, roasted, with oil, salted|14.4|8.3|24.4|51.7
Nut, peanut, without skin, roasted, with oil, unsalted|14.1|6.2|25.1|52.8
Nut, peanut, without skin, roasted, with oil, salted|14.1|6.2|25.1|52.8
Nut, pecan, raw, unsalted|4.9|8.4|9.8|71.9
Nut, pine, raw, unsalted|4.5|5.1|13|70
Nut, pistachio, raw, unsalted|7.8|7.7|17|48.6
Nut, walnut, raw, unsalted|3|6.4|14.4|69.2
Seed, chia, dried|3.1|33.2|23.8|29.8
Seed, hemp, dried|2.5|4.2|29.8|50.6
Seed, linseed or flaxseed|2.8|32.9|21.6|32.6
Seed, poppy|8.6|19.5|18|41.6
Seed, pumpkin, hulled & dried|2.1|4.6|30.2|45
Seed, sunflower|3.1|13|24.7|48.3
Seed, sesame, unsalted|6.2|10.1|22.2|55.6
Wattle seed (acacia), ground|10.5|54.2|20.3|6.1
Flour, hemp|2.7|49.4|22.3|11.1
Tahini, sesame seed pulp|1|13.5|20.4|60.7
Barramundi, aquacultured, fillet, raw|0|0|19.2|5.3
Barramundi, aquacultured, fillet, grilled, no added fat|0|0|26.4|7.3
Barramundi, aquacultured, fillet, steamed with no added fat|0|0|22.9|6.3
Bassa (basa), fillet, raw|0|0|15.7|1.8
Bassa (basa), fillet, baked, no added fat|0|0|21.5|2.4
Bassa (basa), fillet, steamed, no added fat|0|0|18.7|2.1
Blue grenadier (hoki), fillet, raw|0|0|18|1.2
Blue grenadier (hoki), fillet, baked, no added fat|0|0|24.6|1.6
Blue grenadier (hoki), fillet, steamed, no added fat|0|0|21.4|1.4
Bream, fillet, raw|0|0|19.6|5.1
Bream, fillet, baked, no added fat|0|0|26.8|7
Bream, fillet, steamed, no added fat|0|0|22.4|5.4
Flathead, fillet, raw|0|0|21.1|1
Flathead, fillet, baked, no added fat|0|0|28.8|1.4
Flathead, fillet, steamed, no added fat|0|0|26.1|1.2
Gemfish, fillet, raw|0.1|0|14|2.6
Gemfish, fillet, baked, no added fat|0.1|0|19.2|3.6
Gemfish, fillet, steamed, no added fat|0.3|0|26.4|3.1
Kingfish, yellowtail, aquacultured, fillet, with skin, raw|0|0|22.9|12.7
Kingfish, yellowtail, aquacultured, fillet, with skin, grilled, no added fat|0|0|31.3|17.4
Kingfish, yellowtail, aquacultured, fillet, with skin, steamed, no added fat|0|0|27.2|15.1
Mackerel, raw|0|0|19.3|6.3
Mackerel, grilled, no added fat|0|0|26.4|8.6
Mackerel, steamed, no added fat|0|0|23|7.5
Milkfish, aquacultured, fillet, raw|0|0|23.4|10.7
Milkfish, aquacultured, fillet, grilled, no added fat|0|0|32|14.7
Milkfish, aquacultured, fillet, steamed, no added fat|0|0|27.8|12.8
Morwong, fillet, raw|0|0|19.4|5.8
Morwong, fillet, grilled, no added fat|0|0|26.5|7.9
Morwong, fillet, steamed, no added fat|0|0|23|3.7
Mullet, yelloweye, fillet, raw|0|0|19.2|6
Mullet, yelloweye, fillet, baked, no added fat|0|0|26.4|8.2
Mullet, yelloweye, fillet, steamed, no added fat|0|0|22|5
Mulloway, fillet, raw|0|0|20.6|2.3
Mulloway, fillet baked, no added fat|0|0|28.2|3.2
Mulloway, fillet, steamed, no added fat|0|0|23.2|3.9
Salmon, Atlantic, fillet, raw|0|0|20.5|16.7
Salmon, Atlantic, fillet, grilled, no added fat|0|0|22.9|18.6
Salmon, Atlantic, fillet, steamed, no added fat|0|0|24.1|19.6
Salmon, Pacific King, fillet, skinless, raw|0|0|18.1|22.8
Salmon, Pacific King, fillet, skinless, grilled, no added fat|0|0|20.2|25.4
Salmon, Pacific King, fillet, steamed, no added fat|0|0|21.3|26.8
Sardine, Australian, whole, raw|0|0|19.7|2.9
Sardine, Australian, whole, fried, no added fat|0|0|22|1.9
Sprat, blue, wild caught, flesh, skin & bones, raw|0|0|18.7|1.6
Sprat, blue, wild caught, flesh, skin & bones, fried, no added fat|0|0|22|1.9
Shark, fillet, without skin, raw|0|0|24.6|0.4
Shark, fillet, without skin, baked, no added fat|0|0|33.8|0.6
Shark, fillet, without skin, steamed, no added fat|0|0|27.7|0.5
Shark, battered, deep-fried, takeaway outlet|9.9|0.9|21.1|12.8
Silver perch, aquacultured, raw|0|0|19.5|16
Silver perch, aquacultured, baked, no added fat|0|0|26.7|22
Silver perch, aquacultured, steamed, no added fat|0|0|23.2|19.1
Snapper, fillet, raw|0|0|20.3|1.6
Snapper, fillet, baked, no added fat|0|0|27.8|2.2
Snapper, fillet, steamed, no added fat|0|0|27.8|2.2
Tilapia, fillet, raw|0|0|18.5|4.4
Tilapia, fillet, baked, no added fat|0|0|25.3|6
Tilapia, fillet, steamed, no added fat|0|0|22|5.2
Trout, ocean, aquacultured, fillet, without skin, raw|0|0|17.4|24.6
Trout, ocean, aquacultured, fillet, without skin, baked, no added fat|0|0|23.8|33.7
Trout, ocean, aquacultured, fillet, without skin, steamed, no added fat|0|0|20.7|29.3
Trout, rainbow, aquacultured, raw|0|0|19.6|8.4
Trout, rainbow, aquacultured, baked, no added fat|0|0|26.8|11.6
Trout, rainbow, aquacultured, steamed, no added fat|0|0|23.3|10
Tuna, yellowfin, fillet, raw|0|0|23.4|1
Tuna, yellowfin, fillet, baked, no added fat|0|0|32|1.4
Tuna, yellowfin, fillet, steamed, no added fat|0|0|27.8|1.2
Whiting, King George, fillet, raw|0|0|19.8|0.7
Whiting, King George, fillet, baked, no added fat|0|0|28.2|1
Whiting, King George, fillet, steamed, no added fat|0|0|23.3|1.1
Fish, eel, raw|0|0|18.4|11.7
Fish finger, crumbed, purchased frozen, raw|16.6|1.1|12.1|11.6
Fish finger, crumbed, purchased frozen, grilled, no added fat|18.9|1.2|13.8|13.2
Fish, white flesh, battered, packaged frozen, baked, no added fat|20.6|0.5|12.2|7
Fish, white flesh, crumbed, packaged frozen, baked, no added fat|18.8|1.5|13.6|10
Abalone, black lip, aquacultured, raw|1.6|0|16.2|0.8
Abalone, black lip, wild, raw|1.6|0|19.4|0.6
Abalone, green lip, aquacultured, raw|1.6|0|17.1|0.8
Abalone, green lip, wild, raw|1.6|0|18.7|0.5
Abalone, brown lip, wild, raw|1.6|0|17.8|0.6
Crab, flesh, purchased steamed or boiled|0|0|20.2|0.7
Lobster, southern rock, wild, flesh, raw|0|0|20.2|0.7
Lobster or crayfish, flesh, purchased steamed, poached or boiled, no added fat|0|0|22.5|0.8
Mussel, blue, steamed|4.7|0|16|2.3
Octopus, raw|0|0|14.9|1
Octopus, boiled, no added fat|0|0|16.6|1.2
Oyster, native, aquacultured, raw|0.6|0|7.3|1.7
Oyster, Pacific, aquacultured, raw|0.6|0|10.8|2.2
Oyster, Sydney rock, aquacultured, raw|0.6|0|10.6|2.8
Oyster, aquacultured, raw|0.6|0|10.1|2.3
Prawn, banana, wild, flesh, raw|0|0|13.2|0.6
Prawn, banana, aquacultured, purchased cooked|0|0|23.2|1
Prawn, brown tiger, wild, flesh, raw|0|0|23.4|0.9
Prawn, black tiger, aquacultured, purchased cooked|0|0|20.2|1
Prawn, endeavour, wild, flesh, raw|0|0|22.4|0.7
Prawn, school, wild, flesh, raw|0|0|17.8|1.1
Prawn, Western king, wild, flesh, raw|0|0|23.9|0.8
Prawn, flesh, raw (green)|0|0|20.7|0.8
Prawn, flesh, cooked from raw, no added fat|0|0|27.1|1.2
Scallop, raw|3.4|0|15.6|0.8
Scallop, fried, no added fat|4.2|0|19.5|1
Squid or calamari, raw|0|0|16.7|1.2
Squid or calamari, fried, no added fat|0|0|20.9|1.5
Anchovy, canned in oil, drained|0|0|25.4|8.9
Cod, smoked, raw|0|0|18.8|1
Cod or hake, smoked, steamed|0|0|20.1|1.5
Salmon, pink, canned in brine, drained|0|0|20.7|6.2
Salmon, red, canned in brine, drained|0|0|24.4|5.5
Salmon, flavoured, canned|5.5|0|15.5|5
Salmon, smoked, sliced|0|0|24.3|10.1
Tuna, unflavoured, canned in water, drained|0|0|26.1|2.6
Tuna, canned in brine, drained|0|0|26.1|2.6
Tuna, flavoured, canned in oil|3.3|0.3|18|5.8
Snack ball, date based|45|9.9|8.8|11.7
Bar, muesli, plain or with dried fruit|59.2|7|8.3|11.5
Bar, muesli, plain or with dried fruit, chocolate-coated|64.1|6.4|6.1|11.5
Bar, muesli, plain or with dried fruit, yoghurt-coated|59.5|6.4|6.6|14
Bar, muesli, with nuts|54.2|6.8|9.4|19.9
Bar, snack, oat based, baked|44.8|5.7|8|26.7
Bar, snack, fruit filled, baked|69.1|4.5|3.9|3
Bar, snack, with >55% nuts|31|6.8|18.8|34.6
Bar, snack, with >35% nuts, added protein|30|4.8|25|31.9
Extruded snack, cheese flavoured|53.5|3.2|8.8|25.8
Snack, grain based, extruded, flavoured|54.2|6.9|7.4|22.6
Noodle snack, wheat, flavoured, dry|60|1.7|10.4|23.6
Corn chips, plain, salted|56|5.2|7.5|25
Taco shell, from corn flour, plain|56.3|7.3|6.8|21.8
Popcorn, commercial, butter flavoured, salted|49.6|8.8|9.1|26.5
Potato crisps or chips, plain, salted|45.8|3.2|6|33.3
Potato crisps or chips, salt & vinegar flavoured|43.6|3.6|6.8|32.6
Potato crisps or chips, flavoured (other than salt & vinegar)|43.6|3.6|6.8|32.5
Potato crisps or chips, reformed, salted|55.1|2.8|5.7|28.1
Potato straws, French fries, plain|51.6|3.2|6.9|31
Vegetable crisps or chips, sweet potato & taro, flavoured|47.7|6.3|3|31.1
Bar, cherry & coconut centre, dark chocolate-coated|52.6|4.7|4.4|22.4
Bar, coconut cream centre, milk chocolate-coated|52.1|7.4|4.5|22.4
Bar, honeycomb centre, milk chocolate-coated|70|0.3|2.6|16.2
Bar, nougat & caramel centre, milk chocolate-coated|68.9|1.4|3.4|15.9
Bar, wafer & cream layers, chocolate-coated|59.7|1.9|6.2|27.2
Chocolate, compound, cooking|57|4.6|4|30.3
Chocolate, dark, no added sugar|4.1|9.9|6.9|38.3
Chocolate, dark, high cocoa solids|62.6|1.2|3.9|30.3
Chocolate, milk|56|2.3|7.6|30.6
Chocolate, milk, fondant or caramel filled|61.4|2|6|24.7
Chocolate, milk, with dried fruit & nuts|55.4|2.9|7.8|28
Chocolate, milk, with nuts|40.7|4.7|9.9|39.5
Chocolate, white|54.6|0.2|7.1|34.5
Confectionery, almond, chocolate-coated|48.4|3.6|9.4|33.5
Confectionery, chocolate centre, sugar-coated|76.7|1.8|4.8|18.8
Confectionery, dried fruit & nuts, chocolate-coated|49.6|3.8|9.5|29.6
Confectionery, peanut, chocolate-coated|41.9|4.3|13.3|37.7
Confectionery, sultana, chocolate-coated|61.9|2.6|6.1|21.5
Liquorice, chocolate-coated|66|3.2|6.3|9.7
Capers, pickled, canned, drained|1.7|3.2|2.4|0.9
Chutney or relish, commercial|43.1|2.2|0.5|0.3
Jam, berry|67.3|1.6|0.3|0
Jam, plum|64.4|0.9|0.2|0
Jam, stone fruit|64.4|0.9|0.2|0
Marmalade, orange|65.5|0.8|0.1|0
Spread, hazelnut & chocolate flavoured|57.8|3.7|6.8|30.3
Sugar, brown|96.8|0|0.2|0
Sugar, raw|99.8|0|0|0
Sugar, white, granulated or lump|100|0|0|0
Sugar, white, icing|100|0|0|0
Sugar, white, icing mixture|99.6|0|0|0
Glucose, liquid or syrup|87.5|0|0|0
Honey|72.3|0|0.3|0
Syrup, golden|74.9|0|0.2|0
Syrup, maple, pure|67|0|0|0.1
Syrup, malt|75|0|6.2|0
Syrup, rice malt|82.6|0|0.3|0
Treacle or molasses|69.2|0.4|0.6|0
Jelly crystals, sugar sweetened, all flavours|90.7|0|7.1|0
Jelly, sugar sweetened, all flavours, prepared|13.2|0|1|0
Caramels, soft & hard|74.5|1.5|2.5|11.4
Liquorice, allsorts|88.1|1.4|2.3|0.3
Liquorice, black|70.3|3.5|5.7|0.8
Lolly, hard varieties|98.4|0|0|0
Lolly, jelly varieties|75.1|0|3.9|0
Lolly, mint flavoured, sugar sweetened|93.3|0|0.1|2
Lolly, with fruit juice|82.2|0|2.2|0
Meringue, all flavours, commercial|90.1|0|2.6|1.4
Basil, green, fresh, raw|0.2|3.3|2.6|0.5
Chives, raw|2.6|4.7|1.5|0.3
Coriander, fresh, raw|1.9|3.2|3.1|0.6
Dill, fresh, raw|2.5|3.3|3.4|1.2
Marjoram, fresh, raw|0.6|14.1|3.3|5.9
Mint, fresh, raw|4.4|7.2|3.1|1.1
Oregano, fresh, raw|0.6|14.1|3.3|5.9
Parsley, continental, fresh, raw|1.1|5|3.9|0.7
Parsley, curly, fresh, raw|0.4|5.8|1.9|0.2
Rosemary, fresh, raw|0.6|14.1|3.3|5.9
Sage, fresh, raw|0.6|14.1|3.3|5.9
Thyme, fresh, raw|1.1|14|5.6|1.7
Artichoke, globe, raw|1.3|8.1|2.8|0.2
Artichoke, globe, boiled, drained|1.5|9.5|3.3|0.2
Artichoke, jerusalem, peeled, raw|10.5|3|2.1|0.1
Artichoke, jerusalem, peeled, boiled, drained|11.3|3.2|2.2|0.1
Artichoke heart, canned in brine, drained|1.2|4.7|1.9|0.3
Asparagus, green, raw|1.4|2.2|2.5|0.1
Asparagus, green, boiled, drained|1.6|2.5|2.9|0.1
Asparagus, green, fresh or frozen, baked, roasted, stir-fried or fried, grilled or BBQ'd, no added fat|1.7|2.6|3|0.1
Asparagus, canned in brine, drained|1.5|3.9|1.9|0.1
Avocado, hass, raw|2.2|5.9|1.6|14.6
Avocado, shepard, raw|2.2|4.3|1.6|14.6
Bamboo shoot, canned in water, heated, drained|1.3|2.8|0.8|0
Bean, green, fresh, raw|3.3|2.8|1.8|0
Bean, green, fresh, boiled, drained|3.5|3|1.9|0
Bean, green, frozen, boiled, drained|2.8|3.8|1.4|0.2
Bean, green, fresh, cooked, no added fat|3.9|3.2|2.1|0
Beetroot, fresh, purple, peeled, raw|9.4|3.4|2|0.6
Beetroot, fresh, purple, peeled, baked, no added fat|11.4|4.2|2.4|0.7
Beetroot, fresh, purple, peeled, boiled, drained|10|3.7|2.1|0.6
Beetroot, canned in brine, drained|8.7|2|1.2|0
Bok choy, raw|0.6|2.4|2.5|0.2
Bok choy, fried, no added fat|0.8|3.5|3.6|0.3
Bok choy, steamed|0.6|2.6|2.6|0.2
Broccoli, fresh, raw|1.2|3.5|4|0.4
Broccoli, fresh, microwaved|1.3|2.9|3.2|0.4
Broccoli, fresh, boiled, drained|1.2|2.5|2.9|0.3
Broccoli, fresh, baked, no added fat|1.4|4.3|4.9|0.4
Broccolini, fresh, raw|2|2.5|3.2|0.4
Broccolini, fresh, boiled, drained|2.1|2.6|3.3|0.4
Brussels sprout, fresh, raw|4.2|4.6|3.8|0.3
Brussels sprout, fresh, boiled, drained|4.3|4.8|4|0.3
Brussels sprout, fresh, baked, no added fat|4.6|5.1|4.2|0.4
Cabbage, Chinese, raw|0.9|1.1|1.1|0
Cabbage, Chinese, boiled, drained|0.9|1.1|1.1|0
Cabbage, Chinese flowering, raw|0.9|2.8|1.3|0.3
Cabbage, Chinese flowering, boiled, drained|0.9|2.9|1.4|0.3
Cabbage, mustard, raw|0.7|1.8|2.3|0.3
Cabbage, mustard, boiled, drained|0.7|1.9|2.4|0.3
Cabbage, red, raw|3.2|3.1|2.2|0.3
Cabbage, red, boiled, drained|3.3|3.2|2.3|0.3
Cabbage, savoy, raw|2.3|2.3|1.7|0.1
Cabbage, savoy, boiled, drained|2.4|2.4|1.8|0.1
Cabbage, white, raw|3.8|2.2|1.2|0
Cabbage, white, boiled, drained|3.9|2.3|1.2|0
Capsicum, green, fresh, raw|2.5|1.5|0.9|0
Capsicum, green, fresh, fried, no added fat|2.8|1.7|1|0
Capsicum, red, fresh, raw|4.4|1.1|1.1|0.4
Capsicum, red, fresh, fried, no added fat|4.9|1.2|1.2|0.4
Capsicum, yellow, fresh, raw|4.3|1.5|1.1|0.4
Carrot, baby, peeled, fresh, raw|5.5|3.3|0.7|0.1
Carrot, baby, peeled, fresh, boiled, drained|5.9|3.5|0.8|0.1
Carrot, baby, baked, no added fat|6|3.6|0.8|0.1
Carrot, mature, peeled, fresh, raw|6.6|3.2|0.6|0
Carrot, mature, peeled, fresh, baked, no added fat|8|3.9|0.7|0
Carrot, mature, peeled, fresh, boiled, drained|5.1|3.3|1.1|0.3
Cassava, peeled, fresh, raw|30.4|4.6|1.1|0.2
Cassava, peeled, fresh, boiled, drained|32.7|5|1.2|0.3
Cassava, white flesh, peeled, fresh, raw|34.1|4.6|0.6|0.3
Cassava, white flesh, peeled, fresh, boiled, drained|36.7|4.9|0.6|0.3
Cassava, yellow flesh, peeled, fresh, raw|26.7|4.7|1.6|0.2
Cassava, yellow flesh, peeled, fresh, boiled, drained|28.7|5|1.7|0.2
Cauliflower, fresh, raw|3.3|1.2|1.9|0.2
Cauliflower, fresh, boiled, drained|3.4|1.2|2|0.2
Cauliflower, fresh, baked, no added fat|4|1.5|2.3|0.2
Celeriac, peeled, raw|2.8|5.1|1.6|0.2
Celeriac, peeled, boiled, drained|3|5.5|1.7|0.2
Celery, fresh, raw|1.8|1.6|0.6|0.1
Celery, fresh, fried, no added fat|2.1|1.9|0.8|0.1
Chicory, raw|0.8|1.8|1.8|0.2
Chicory, boiled, drained|0.9|2.1|2.1|0.2
Chilli (chili), green, raw|1|12.9|2.8|0.3
Chilli (chili), green, fried, no added fat|1.1|14.3|3.1|0.3
Chilli (chili), red, raw|4.8|7.2|1.4|0.7
Chilli (chili), red, fried, no added fat|5.3|8|1.6|0.8
Choko, peeled, fresh, raw|3.6|1.4|0.5|0.2
Choko, peeled, fresh, boiled, drained|4.3|1.7|0.6|0.2
Corn, fresh on cob, raw|12.5|4.2|3.6|1.8
Corn, fresh on cob, boiled, drained|13|4.4|3.8|1.9
Sweetcorn, fresh or frozen on cob, baked, roasted, fried, stir-fried, grilled or BBQ'd, no added fat|13.9|4.7|4|2
Corn, kernels, purchased frozen, raw|16.8|2.5|3|0.9
Corn, kernels, purchased frozen, boiled, drained|19.9|3.5|3.1|0.9
Corn, kernels, canned in brine, drained|16.8|2.7|2.9|1
Cucumber, common, peeled, raw|2.1|0.6|0.4|0.1
Cucumber, common, unpeeled, raw|1.2|1.4|0.8|0.1
Cucumber, Lebanese, peeled, raw|1.9|0.7|0.7|0
Cucumber, Lebanese, unpeeled, raw|1.8|0.6|0.7|0
Cucumber, telegraph, unpeeled, raw|1.4|0.9|1.2|0.1
Eggplant, unpeeled, fresh, raw|2.6|2.6|1|0.2
Eggplant, unpeeled, fresh, roasted, no added fat|4.2|4.5|1.4|0.2
Endive, fresh, raw|0.4|2.2|1.5|0.2
Fennel, fresh, raw|3.3|1.8|1|0.1
Fennel, fresh, boiled, drained|3.9|2.1|1.2|0.1
Garlic, peeled, fresh, raw|10.2|16.9|6.1|2.8
Garlic, peeled, fresh, fried, no added fat|16.4|27.2|9.8|4.5
Gherkin, pickled, drained, commercial|24.4|0.9|0.4|0.3
Ginger, peeled, fresh, raw|4.8|2.8|0.8|0.4
Ginger, peeled, fresh, fried, no added fat|5.8|3.4|1|0.5
Kale, raw|0.3|5.2|3.3|0
Kale, fried, no added fat|0.4|7.5|4.7|0
Kohlrabi, peeled, fresh, raw|4.2|3.3|3.7|0.1
Kohlrabi, peeled, fresh, boiled, drained|4.4|3.4|3.8|0.1
Leek, fresh, raw|4.1|3.1|1.6|0.2
Leek, fresh, fried, no added fat|4.9|3.8|2|0.2
Leek, fresh, boiled, drained|4.6|3.5|1.8|0.2
Lettuce, cos, raw|1.8|1.8|1.2|0.2
Lettuce, iceberg, raw|2.1|1.1|1|0
Lettuce, mignonette, raw|1.1|1.4|1.3|0.3
Melon, bitter, fresh, raw|0.2|3.3|0.9|0
Melon, hairy, fresh, raw|2|2.1|0.7|0
Mushroom, common, fresh, raw|0.8|1.4|2.2|0.8
Mushroom, common, fresh, fried, no added fat|3.1|2.7|5|1.4
Mushroom, common, vitamin D enhanced, fresh, raw|0|1.4|2.2|0.4
Mushroom, common, vitamin D enhanced, fresh, fried, no added fat|0|2.5|4|0.7
Okra, raw|1.4|3.7|3.1|0.2
Okra, boiled, drained|1.5|4|3.3|0.2
Olive, green or black, drained|0|3.2|2.2|21.7
Onion, mature, brown skinned, peeled, raw|5.8|2.3|1.3|0
Onion, mature, brown skinned, peeled, fresh, fried, no added fat|9.4|3.8|2.1|0
Onion, mature, red skinned, raw|5.5|1.8|0.8|0
Onion, mature, red skinned, peeled, fried, no added fat|8.9|2.8|1.2|0
Onion, mature, white skinned, peeled, fresh, raw|4.7|2.1|1.7|0.1
Onion, spring, fresh, raw|4.6|1.9|1.7|0.2
Onion, spring, fresh, fried, no added fat|7.4|3.1|2.7|0.3
Parsnip, peeled, fresh, raw|10.1|4.1|1.6|0.4
Parsnip, peeled, fresh, baked, no added fat|12.3|5|2|0.5
Parsnip, peeled, fresh, boiled, drained|10.9|4.4|1.8|0.4
Pea, green, fresh, raw|7.7|6.6|6.5|0.5
Pea, green, fresh, boiled, drained|6.4|5.2|4.8|0.4
Pea, green, frozen, boiled, drained|6.4|6.1|5.2|0.3
Potato, coliban, peeled, raw|11.5|1.1|2|0
Potato, coliban, peeled, baked, no added fat|17.4|1.7|3|0
Potato, coliban, peeled, boiled, drained|11.6|2|1|0
Potato, desiree, peeled, raw|10.7|1.7|2.3|0
Potato, desiree, peeled, baked, no added fat|16.2|2.6|3.5|0
Potato, desiree, peeled, boiled, drained|10.9|1.7|2.3|0
Potato, new, peeled, raw|12.8|1.1|2.3|0.1
Potato, new, peeled, baked, no added fat|19.4|1.7|3.5|0.2
Potato, new, peeled, boiled, drained|12.8|2.3|2.5|0.1
Potato, pale skin, peeled, raw|11.5|1.1|2|0
Potato, pale skin, peeled, boiled, drained|12.1|2|1.5|0.1
Potato, pale skin, peeled, baked, no added fat|17.4|1.7|3|0
Potato, pontiac, peeled, raw|12.6|2.1|2.4|0.1
Potato, pontiac, peeled, baked, no added fat|19.1|3.2|3.6|0.2
Potato, pontiac, peeled, boiled, drained|12.8|2.1|2.4|0.1
Potato, red skin, peeled, raw|10.7|1.7|2.3|0
Potato, red skin, peeled, baked, no added fat|16.2|2.6|3.5|0
Potato, red skin, peeled, boiled, drained|14.2|2.4|2|0
Potato, sebago, peeled, raw|13.8|2.2|2.5|0.2
Potato, sebago, peeled, baked, no added fat|20.8|3.3|3.8|0.2
Potato, sebago, peeled, boiled, drained|13.1|2.5|2.5|0.4
Potato, chips, regular, fast food outlet, deep fried, blended oil, salted|28.2|3|3.8|10.1
Potato, chips, regular, fast food outlet, deep fried, monounsaturated oil, salted|28.2|3|3.8|12.2
Potato, chips, regular, independent takeaway outlet, deep fried, blended oil, salted|28.2|3|3.8|11.6
Potato, chips, regular, purchased frozen, baked, no added fat|24.1|3.8|3.8|5.9
Potato, fries, fast food outlet, deep fried, monounsaturated oil, salted|35.7|3.1|4.8|16.4
Potato, fries, independent takeaway outlet, deep fried, blended oil, salted|35.7|3.1|4.8|17.4
Potato, wedges, regular, purchased frozen, baked or roasted, no added fat|24.1|3.8|3.8|5.9
Pumpkin, butternut, peeled, fresh, raw|6.9|1.8|2.1|0.6
Pumpkin, butternut, peeled, fresh, baked, no added fat|8.1|2.1|2.5|0.7
Pumpkin, butternut, peeled, fresh, boiled, drained|7.2|1.9|2.2|0.6
Pumpkin, golden nugget, peeled, fresh, raw|4|1.1|2.2|0.3
Pumpkin, golden nugget, peeled, fresh, baked, no added fat|4.7|1.3|2.6|0.4
Pumpkin, golden nugget, peeled, fresh, boiled, drained|4.2|1.1|2.3|0.3
Pumpkin, jarrahdale, peeled, fresh, raw|4.3|1.6|0.7|0.1
Pumpkin, jarrahdale, peeled, fresh, baked, no added fat|5|1.9|0.8|0.1
Pumpkin, jarrahdale, peeled, fresh, boiled, drained|4.5|1.7|0.7|0.1
Pumpkin, kent/jap, peeled, fresh, raw|4.3|1.8|1|0.3
Pumpkin, kent/jap, peeled, fresh, boiled, drained, no added fat|4.5|1.9|1|0.3
Pumpkin, kent/jap, peeled, fresh, baked, no added fat|5|2.1|1.2|0.4
Pumpkin, Queensland blue, peeled, fresh, raw|8.4|2.7|2|0.3
Pumpkin, Queensland blue, peeled, fresh, baked, no added fat|9.9|3.2|2.4|0.4
Pumpkin, Queensland blue, peeled, fresh, boiled, drained|8.8|2.8|2.1|0.3
Pumpkin, peeled, fresh, raw|6.7|1.9|1.2|0
Pumpkin, peeled, fresh, baked, no added fat|7.9|2.2|1.5|0
Pumpkin, peeled, fresh, boiled, drained|7|2|1.3|0
Radish, red skinned, unpeeled, raw|1.9|1.1|0.8|0.2
Radish, white skinned, peeled, raw|2.9|1.8|0.7|0.3
Rocket, fresh, raw|0.3|2.4|3.3|0.3
Seaweed, nori, dried|2.3|34.3|46.7|4.3
Seaweed, boiled, drained|0.4|6.2|8.4|0.8
Shallot, peeled, fresh, raw|3.1|2.9|1.5|0.1
Shallot, peeled, fresh, fried, no added fat|5|4.7|2.4|0.2
Silverbeet, fresh, raw|1.4|2.3|2.1|0.2
Silverbeet, fresh, boiled, drained|1.7|2.7|2.5|0.3
Snow pea, fresh, raw|5.6|2.6|3|0
Snow pea, fresh, fried, no added fat|7|3.2|3.8|0
Snow pea, fresh, boiled, drained|6|2.8|3.2|0
Spinach, baby, fresh, raw|0|2.5|2.8|0
Spinach, Mature English, fresh, raw|0.6|2.5|2.4|0.3
Spinach, Mature English, fresh, boiled, drained|0.7|2.9|2.8|0.4
Spinach, water, fresh, raw|1|2.8|2.9|0.5
Sprout, alfalfa, fresh, raw|0.5|2.2|3.2|0.3
Sprout, bean, fresh, raw|1.6|1.3|3.1|0.1
Squash, button, fresh, raw|3.2|0.5|2.7|0.2
Squash, button, fresh, boiled, drained|3.8|0.6|3.2|0.2
Squash, scallopini, fresh, raw|2.1|0.5|2.2|0.2
Squash, scallopini, fresh, boiled, drained|2.5|0.6|2.6|0.2
Swede, peeled, fresh, raw|3.7|2.2|0.9|0
Swede, peeled, fresh, boiled, drained|4|2.4|1|0
Sweet potato, orange flesh, peeled, fresh, raw|13.2|2.6|1.4|0.2
Sweet potato, orange flesh, unpeeled, raw|13.2|2.7|1.4|0.2
Sweet potato, orange flesh, peeled, fresh, baked, no added fat|16.1|3.2|1.8|0.2
Sweet potato, orange flesh, peeled, fresh, boiled, drained|14.2|2.8|1.6|0.2
Sweet potato, purple flesh, peeled, raw|25.5|2.7|1.4|0.2
Sweet potato, purple flesh, peeled, baked, no added fat|31.1|3.3|1.8|0.2
Sweet potato, white flesh, peeled or unpeeled, fresh or frozen, baked, roasted, fried, stir-fried, grilled or BBQ'd, no added fat|29.5|3.9|1.8|0.2
Sweet potato, white flesh, peeled, raw|24.2|3.2|1.4|0.2
Taro, peeled, fresh, raw|23.4|3.5|1.9|0.2
Taro, peeled, fresh, boiled, drained|25.2|3.8|2|0.2
Tomato, cherry, raw|3.5|1.7|0.9|0
Tomato, common, raw|3|1.1|0.6|0
Tomato, paste, with added salt|10.4|3.5|3.1|0.3
Tomato, paste, no added salt|10.1|3.6|3.4|0.5
Tomato, puree, commercial|3.8|1.5|1.2|0.1
Tomato, sundried|35.2|14.7|11.2|4.6
Tomato, whole, canned in tomato juice, undrained|3|1|0.9|0
Tomato, whole, canned in tomato juice, drained|3|1.3|0.8|0.3
Tomato, whole, canned in tomato juice, no added salt, undrained|3|1|0.9|0
Turnip, white, peeled, fresh, raw|3.4|1.7|1.3|0
Turnip, white, peeled, fresh, boiled, no added fat|3.6|1.8|1.4|0
Vine leaf, grape, canned|1.8|9.9|4.3|2
Water chestnut, peeled, canned, drained|9.3|2.7|0.6|0.9
Watercress, raw|0.8|3.8|2.9|0.4
Yam, wild harvested, cooked|20.9|3.5|3.2|0.3
Zucchini, green skin, fresh, unpeeled, raw|2.2|1|1|0.2
Zucchini, green skin, fresh, unpeeled, fried, no added fat|3.1|1.5|1.5|0.2
Zucchini, green skin, fresh, unpeeled, boiled, drained|2.6|1.2|1.2|0.2
Zucchini, golden, fresh, unpeeled, raw|1.1|1|2.2|0.3
Zucchini, golden, fresh, unpeeled, boiled, drained|1.3|1.2|2.6|0.4
Mixed leafy greens, pre-packaged, all types|0|2|2.2|0
Mixed vegetables, purchased frozen, carrot, corn & pea/bean, boiled, drained|5.9|5.9|1.8|0.3
Stock, liquid, all flavours (except fish), prepared from commercial powder or cube|0.4|0|0.2|0.2|l
Vanilla, artificial or imitation|14.4|0|0|0|l
Beer, high alcohol (5% v/v & above)|2.1|0|0.5|0|l
Beer, full strength (alcohol 4-4.9% v/v)|2.3|0|0.4|0|l
Beer, full strength (alcohol 4-4.9% v/v), carbohydrate modified|1|0|0.3|0|l
Beer, mid-strength (alcohol 3-3.9% v/v)|2.2|0|0.3|0|l
Beer, light (alcohol 1-2.9% v/v)|2.2|0|0.2|0|l
Alcoholic beverage, spirit, ~30% v/v, all (Brandy, Gin, Rum, Vodka and Whisky), cooked|0.3|0|0|0|l
Alcoholic beverage, spirit, ~40% v/v, all (Brandy, Gin, Rum, Vodka and Whisky)|0.1|0|0|0|l
Cider, apple (alcohol ~4-5% v/v)|5.6|0|0|0|l
Mirin|26.6|0|0.3|0|l
Wine, red|0.2|0|0.2|0|l
Wine, red, cabernet sauvignon|0.2|0|0.3|0|l
Wine, red, merlot|0.4|0|0.3|0|l
Wine, red, pinot noir|0.2|0|0.2|0|l
Wine, red, shiraz|0.1|0|0.2|0|l
Wine, red, sparkling|2|0|0.2|0|l
Wine, red, cooked|0.2|0|0.3|0|l
Wine, rose|0.7|0|0.2|0|l
Wine, white, sauvignon blanc|0.1|0|0.2|0|l
Wine, white, semillon|0.2|0|0.2|0|l
Wine, white, chardonnay|0.3|0|0.3|0|l
Wine, white, riesling|0.4|0|0.2|0|l
Wine, white|0.2|0|0.3|0|l
Wine, white, cooked|0.3|0|0.2|0|l
Wine, white, sparkling|1|0|0.2|0|l
Wine, white, sweet dessert style|15.3|0|0.4|0|l
Wine, fortified, port|12.9|0|0.2|0|l
Wine, fortified, sherry, dry style (~1% sugars)|1.2|0|0.1|0|l
Wine, fortified, sherry, sweet style (~11% sugars)|11.4|0|0.2|0|l
Beverage, chocolate flavour, from Milo powder, with regular fat cow's milk|10.5|0.6|4.2|4.1|l
Beverage, chocolate flavour, from drinking chocolate, with regular fat cow's milk|10.6|0.1|3.6|3.6|l
Protein powder, whey based, protein >70%, unfortified, prepared with water|0.3|0.3|14.3|0.4|l
Coffee, prepared from coffee mix with sugar & whitener, no added milk|2.8|0.2|0.6|0.6|l
Coffee, black, from instant coffee powder|0.2|0.1|0.2|0|l
Coffee, black, from instant coffee powder, decaffeinated|0.2|0.1|0.2|0|l
Coffee, espresso, from ground coffee beans|0|0|1.7|0.3|l
Coffee, long black, from ground coffee beans|0|0|0.3|0.1|l
Coffee, flat white/latte/cappuccino, from ground coffee beans, with regular fat cow's milk|3.5|0|2.9|2.6|l
Cordial base, 25% citrus fruit juice, regular|45.5|0|0|0|l
Cordial base, 40% citrus fruit juice, regular|49|0|0.2|0|l
Cordial base, blackcurrant juice, regular|54.9|0|0.1|0|l
Cordial base, fruit juice/flavours, intense sweetened or diet|7.2|0|0|0|l
Cordial, 25% citrus fruit juice, regular, recommended dilution|9.2|0|0|0|l
Cordial, 40% citrus fruit juice, regular, recommended dilution|9.9|0|0|0|l
Cordial, blackcurrant juice, regular, recommended dilution|9.8|0|0|0|l
Cordial, fruit juice/flavours, recommended dilution|6.9|0|0|0|l
Cordial, fruit juice/flavours, intense sweetened or diet, recommended dilution|0.7|0|0|0|l
Juice, apple, commercial, added vitamin C|7.4|0.2|0.2|0|l
Juice, apple & blackcurrant, commercial|9.7|0|0.2|0|l
Juice, lemon|1.9|2.1|0.7|0.1|l
Juice, lime|1.3|3.8|0.8|0.1|l
Juice, orange, commercial|7.1|0.4|0.8|0|l
Juice, orange & mango, commercial|8|0.2|0.6|0|l
Juice, vegetable blend, commercial|2.8|0.6|0.9|0|l
Fruit drink, apple juice|8.5|0|0.1|0|l
Fruit drink, blackcurrant, commercial|10.2|0|0.2|0|l
Fruit drink, cranberry|12.7|0.1|0|0|l
Fruit drink, orange juice|10|0.1|0.2|0|l
Soft drink, cola flavour|12.8|0|0|0.3|l
Soft drink, cola flavour, decaffeinated|12.8|0|0|0.3|l
Soft drink, cola flavour, intense sweetened or diet|0|0|0|0|l
Soft drink, cola flavour, intense sweetened or diet, decaffeinated|0|0|0|0|l
Soft drink, dry ginger ale or ginger beer, regular|10.2|0|0|0|l
Soft drink, fruit flavours|9.2|0|0|0|l
Soft drink, tonic water|9.1|0|0|0|l
Soft drink, fruit flavours, intense sweetened or diet|0|0|0|0|l
Soft drink, tonic water, intense sweetened or diet|0|0|0|0|l
Soft drink, energy drink, Red Bull|11.3|0|0.1|0|l
Soft drink, energy drink, V|11.3|0|0.1|0|l
Kombucha, flavoured, added juice or intense sweeteners|0.8|0.1|0|0|l
Tea, regular, black, brewed from leaf or teabags, without milk|0|0|0.1|0|l
Tea, black, brewed from leaf or teabags, decaffeinated|0|0|0.1|0|l
Tea, green, plain, without milk|0|0|0.2|0|l
Mineral water, natural, unflavoured|0|0|0|0|l
Mineral water, citrus flavoured|3.8|0|0|0|l
Water, bottled, still|0|0|0|0|l
Water, soda|0|0|0|0|l
Water, tap|0|0|0|0|l
Breakfast cereal, beverage, non-chocolate flavours, added vitamins & minerals (Up & Go)|12.8|0.8|3.2|1.5|l
Dressing, French or Italian, regular fat, commercial|12|0|0.1|20.2|l
Dressing, thousand island, regular fat, commercial|17.9|1.5|1.1|34.3|l
Mayonnaise, traditional (>65% fat), commercial|8.6|0.5|0.8|66.5|l
Mayonnaise, low fat, commercial|29.3|0|0.5|1.9|l
Gravy, prepared from dry powder with water|4.6|0|0.7|0.5|l
Mustard, cream style|6.5|4.3|6.1|3.2|l
Sauce, barbecue, commercial|47.7|1|0.9|0.1|l
Sauce, butter chicken, commercial|6.6|2.1|1.5|8.5|l
Sauce, cranberry, commercial|46.8|1.3|0|0|l
Sauce, fish, commercial|5.4|1.5|8.3|0.4|l
Sauce, hoi sin (hoisin), commercial|40.2|11.2|1.8|5.8|l
Sauce, oyster, commercial|25.6|0.4|3.4|1.2|l
Sauce, pasta or simmer, commercial, low fat|7.4|1.5|0.9|3.3|l
Sauce, pasta, basil pesto, commercial|8.5|1.7|6.1|42.7|l
Sauce, pasta, cheese or cream-based, commercial|5.6|0.1|1.4|7.2|l
Sauce, pasta, tomato-based, commercial, heated|8.2|2|1.8|1.6|l
Sauce, plum, commercial|72.9|0.8|0.2|1|l
Sauce, rogan josh, commercial|7|2.1|1.7|4.6|l
Sauce, salsa, tomato-based, commercial|6.9|1.8|1.4|0|l
Sauce, simmer for chicken, commercial|5.4|1.9|0.9|8.5|l
Sauce, simmer, curry flavoured, commercial|12.1|2.6|2.4|7.8|l
Sauce, soy, commercial|3.4|0|7.2|0|l
Sauce, soy, commercial, reduced salt|3.4|0|7.2|0|l
Sauce, tabasco, commercial|0.1|0.6|1.3|0.7|l
Sauce, tomato, commercial|25.1|1.4|1.4|0|l
Sauce, tomato, commercial, reduced salt|25.1|1.4|1.4|0|l
Sauce, tomato, reduced sugar & salt, commercial|11.7|1.4|1.4|0|l
Sauce, white, savoury, homemade|11.5|0.2|4.2|14.3|l
Sauce, Worcestershire, commercial|21.5|0|1.5|0.2|l
Vinegar (except balsamic vinegar)|0|0|0|0|l
Vinegar, balsamic|15.2|2.1|0.5|0|l
Cream, thickened, regular fat (~35%)|2.5|0|2.1|37.6|l
Cream, sour, regular fat|2|0|1.9|35.6|l
Cream, sour, light (~18% fat)|4.6|0|3.7|20.1|l
Cream, imitation (non-dairy)|17.9|0|1|22|l
Ice cream, vanilla flavour, regular fat|11.3|0|2|6.1|l
Ice cream, vanilla flavour, premium or rich (~15% fat)|14.2|0|2|8.5|l
Milk, cow, fluid, regular fat (~3.5%)|5.5|0|3.4|3.5|l
Milk, cow, fluid, prepared from dry powder, regular fat, standard dilution|6.3|0|3.7|3.7|l
Milk, cow, fluid, regular fat (~3.5%), added omega 3 polyunsaturates|5.2|0|3.2|3.3|l
Milk, cow, fluid, reduced fat (~1%)|5|0|3.5|1.2|l
Milk, cow, fluid, reduced fat (~1.5%), added omega 3 polyunsaturates|5.3|0|4.7|1.4|l
Milk, cow, fluid, skim (~0.15% fat)|5.5|0|3.7|0|l
Milk, cow, fluid, prepared from dry powder, skim, standard dilution|6.2|0|3.9|0.1|l
Milk, cow, fluid, lactose free, regular fat (~3.5%)|4.8|0|3.2|3.2|l
Milk, cow, fluid, lactose free, reduced fat (~1%)|5.1|0.1|3.4|1.2|l
Milk, cow, canned, evaporated, regular|10.7|0|8.1|8.8|l
Milk, cow, canned, evaporated, reduced fat (~2%)|11.4|0|8.4|2.3|l
Milk, cow, canned, sweetened, condensed, regular|71|0|10.7|11.9|l
Milk, cow, fluid, flavoured, chocolate, reduced fat|9|0|3.5|2|l
Milk, human/breast, mature, fluid|7.1|0|1.4|4.4|l
Custard, vanilla, regular fat|16.4|1.3|3.5|3.4|l
Custard, vanilla, prepared from dry mix|15.6|0|4|4.1|l
Yoghurt, natural, regular fat (~3%)|5.2|0.1|5.3|3.3|l
Yoghurt, natural, sheep's milk|2.3|0|6.1|6.8|l
Yoghurt, flavoured, high fat (~5%)|13.4|0.6|4.6|5.1|l
Yoghurt, apricot pieces or flavoured, regular fat (~3%)|11.5|0.1|4.4|3.3|l
Yoghurt, strawberry pieces or flavoured, regular fat (~3%)|12.1|0.1|5.1|3.2|l
Yoghurt, flavoured, low fat (~2%)|12.7|0.3|4.8|1.4|l
Yoghurt, vanilla flavoured (~2%)|15|0.2|5.2|2|l
Yoghurt, flavoured, low fat (0.2%), intense sweetened, increased protein|2.4|0.4|10|0.3|l
Yoghurt, vanilla flavoured, low fat (<0.5%)|14.5|0.2|6.1|0.5|l
Yoghurt, almond based, flavoured|9.6|1.9|2.7|4.7|l
Yoghurt, coconut based, flavoured|9.6|1.4|1.1|9.4|l
Yoghurt, soy based, berry flavoured, regular fat (~3%)|13.1|0.2|3.8|3.8|l
Yoghurt, soy based, vanilla flavoured, reduced fat (~1%)|13.1|0.2|3.8|0.8|l
Dairy or yoghurt dessert, chocolate, regular fat|15.2|1.4|2.8|4.7|l
Almond beverage, added sugar, unfortified|3.2|0.6|0.5|1.5|l
Almond beverage, no added sugar, unfortified|0|0.5|0.5|1.5|l
Almond beverage, added sugar & Ca|3.2|0.6|0.5|1.5|l
Almond beverage, no added sugar, added Ca|0|0.5|0.5|1.5|l
Almond beverage, added sugar & vitamins B1, B2, B12, C & Ca|3.2|0.6|0.5|1.5|l
Coconut beverage, unfortified|2.5|0|0.2|1.8|l
Coconut beverage, added Ca|2.5|0|0.2|1.8|l
Oat beverage, fluid, unfortified|10.7|0.3|1.4|0.8|l
Oat beverage, fluid, added Ca|10.7|0.3|1.4|0.8|l
Oat beverage, fluid, added calcium & vitamins|6.2|0.5|0.5|1.7|l
Rice beverage, unfortified|9.4|0|0.6|1|l
Rice beverage, fluid, added calcium|13.4|0|0.3|1|l
Soy beverage, regular fat (~3%), unfortified|4.8|0.6|3.8|2.8|l
Soy beverage, regular fat (~3%), added Ca|4.8|0.6|3.8|2.8|l
Soy beverage, regular fat (~3%), added Ca & vitamins A, B1, B2 & B12|4.8|0.6|4.5|2.8|l
Soy beverage, reduced fat (~1%), added Ca & vitamins A, B1, B2 & B12|5.9|0.6|3.1|0.9|l
Soup, chicken & noodle, cup of soup, prepared from instant dry mix with water|3.5|0.3|0.7|0.2|l
Oil, almond|0|0|0|92|l
Oil, avocado|0|0|0|92|l
Oil, canola|0|0|0|92|l
Oil, coconut|0|0|0|91.6|l
Oil, copha|0|0|0.2|91.9|l
Oil, cottonseed|0|0|0|92|l
Oil, grapeseed|0|0|0|92|l
Oil, hemp|0|0|0|91.9|l
Oil, linseed or flaxseed|0|0|0|92|l
Oil, macadamia|0|0|0|92|l
Oil, maize|0|0|0|92|l
Oil, mustard seed|0|0|0|92|l
Oil, olive|0|0|0|91.9|l
Oil, palm|0|0|0|92|l
Oil, peanut|0|0|0|92|l
Oil, rice bran|0|0|0|92|l
Oil, safflower|0|0|0|92|l
Oil, sesame|0|0|0|92|l
Oil, soybean|0|0|0|92|l
Oil, sunflower|0|0|0|92|l
Oil, blend of polyunsaturated vegetable oils|0|0|0|92|l
Oil, blend of monounsaturated vegetable oils|0|0|0|92|l
Oil, vegetable|0|0|0|92|l
Apricot, canned in pear juice, juice only|9.1|0|0.5|0|l
Cherry, black, canned in syrup, syrup only|16.6|0|0.5|0|l
Mandarin, canned in syrup, syrup only|13.7|0|0.3|0|l
Peach, canned in pear juice, juice only|9.1|0.3|0.4|0|l
Pear, canned in pear juice, juice only|9.5|0.2|0.3|0|l
Pineapple, canned in pineapple juice, juice only|11|0|0.3|0|l
Pineapple, canned in syrup, syrup only|23.8|0|0.3|0|l
Plum, dark, canned in syrup, syrup only|29.6|0|0.4|0|l
Raspberry, canned in syrup, syrup only|24.3|0|0.3|0|l
Strawberry, canned in syrup, syrup only|26.3|0|0.2|0.3|l
Fruit salad, canned in fruit juice, juice only|10.6|0.2|0.4|0|l
Dip, hummus (hommus), commercial|9.4|3.9|6.9|19.5|l
Coconut, fresh, mature, water or juice|4.7|0|0.5|0.1|l
Coconut, fresh, young or immature, water or juice|6.6|0|0.2|0|l
Water, coconut, commercial|3.2|0|0.2|0|l
Coconut, cream, regular fat|1.4|0|1.6|23.7|l
Coconut, milk, canned, regular fat|1.7|0|1.4|15.7|l
Coconut, milk, canned, reduced fat|0.9|0|0.6|7.2|l
Tahini, sesame seed pulp|1|13.5|20.4|60.7|l
Glucose, liquid or syrup|121.6|0|0|0|l
Honey|103.4|0|0.4|0|l
Syrup, golden|107.8|0|0.4|0|l
Syrup, maple, pure|89.1|0|0|0.1|l
Syrup, malt|99.8|0|8.2|0|l
Syrup, rice malt|109.8|0|0.4|0|l
Treacle or molasses|98.3|0.6|0.8|0.1|l
"""
)
