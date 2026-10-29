"""Catalog data for seed-business-shops.py: a take-away restaurant and a fashion shop (Arabic, Egyptian prices in EGP).

Restaurant dish: (key, name, category slug, base price, [(option, [(value, price added)])], specs, badge, compare-at base or None, photo query, tags)
Fashion item:    (key, name, category slug, audience, colours, (size scale, sizes) or None, price, sale price or None, photo query, badge, extra option or None)
Colours and sizes must be the platform's standard values (commerce.attribute_values).
"""

# ---------------------------------------------------------------- restaurant
SIZE3 = lambda a, b: ("الحجم", [("عادي", 0), ("كبير", a), ("سوبر", b)])
BREAD = ("الخبز", [("صاج", 0), ("عيش سوري", 0), ("فينو", 0)])
SPICE = ("الطعم", [("عادي", 0), ("حار", 0)])
DRINK = ("المشروب", [("بيبسي", 0), ("سفن أب", 0), ("ميرندا", 0)])
WEIGHT3 = lambda a, b: ("الكمية", [("ربع كيلو", 0), ("نص كيلو", a), ("كيلو", b)])
WEIGHT2 = lambda a: ("الكمية", [("ربع كيلو", 0), ("نص كيلو", a)])
SMALL_BIG = lambda a: ("الحجم", [("صغير", 0), ("كبير", a)])


def food_specs(ingredients, kcal, spicy="خفيف", serves="شخص واحد"):
    return [{"k": "المكونات", "v": ingredients}, {"k": "السعرات (تقريبي)", "v": kcal}, {"k": "درجة الحرارة", "v": spicy}, {"k": "يكفي", "v": serves}]


RESTAURANT = [
    # shawarma
    ("sh-chicken", "ساندوتش شاورما فراخ", "shawarma", 8500, [SIZE3(2500, 5500), BREAD], food_specs("صدور فراخ متبلة، ثومية، مخلل، بطاطس", "520 سعر للعادي"), "BEST_SELLER", None, "chicken shawarma sandwich", ["الأكثر طلباً"]),
    ("sh-meat", "ساندوتش شاورما لحمة", "shawarma", 11000, [SIZE3(3000, 6500), BREAD], food_specs("لحم بتلو متبل، طحينة، بصل بالسماق، طماطم", "610 سعر للعادي"), "BEST_SELLER", None, "shawarma beef|shawarma|doner kebab", ["الأكثر طلباً"]),
    ("sh-mix", "ساندوتش شاورما ميكس فراخ ولحمة", "shawarma", 12500, [("الحجم", [("عادي", 0), ("كبير", 3500)]), BREAD], food_specs("فراخ ولحمة، ثومية وطحينة، مخلل", "640 سعر"), "", None, "shawarma wrap|shawarma|durum", []),
    ("sh-cheese", "شاورما فراخ بالجبنة الموتزاريلا", "shawarma", 11000, [("الحجم", [("عادي", 0), ("كبير", 2500)]), BREAD], food_specs("فراخ، موتزاريلا سايحة، ثومية", "700 سعر"), "NEW", None, "shawarma cheese|shawarma|wrap sandwich", ["جديد"]),
    ("sh-arabi", "وجبة شاورما فراخ عربي", "shawarma", 14500, [("الحجم", [("عادي", 0), ("دبل", 5000)])], food_specs("شاورما مقطعة في عيش سوري، بطاطس، ثومية، مخلل", "880 سعر"), "BEST_SELLER", None, "shawarma arabi", ["الأكثر طلباً"]),
    ("sh-plate-ch", "صحن شاورما فراخ", "shawarma", 15000, [("الكمية", [("ربع كيلو", 0), ("نص كيلو", 11000)])], food_specs("شاورما فراخ، أرز أو بطاطس، ثومية، سلطة، عيش", "900 سعر للربع", serves="شخص أو اتنين"), "", None, "chicken shawarma plate|shawarma plate|shawarma", []),
    ("sh-plate-meat", "صحن شاورما لحمة", "shawarma", 18000, [("الكمية", [("ربع كيلو", 0), ("نص كيلو", 14000)])], food_specs("شاورما لحمة، أرز، طحينة، سلطة، عيش", "980 سعر للربع", serves="شخص أو اتنين"), "", None, "shawarma platter meat", []),
    ("sh-fatta-ch", "فتة شاورما فراخ", "shawarma", 12000, [SMALL_BIG(4500)], food_specs("عيش محمص، أرز، صلصة ثومية بالزبادي، شاورما فراخ", "850 سعر للصغير"), "", None, "fatteh chicken|fatteh|fatta", []),
    ("sh-fatta-meat", "فتة شاورما لحمة", "shawarma", 15000, [SMALL_BIG(5000)], food_specs("عيش محمص، أرز، صلصة طحينة، شاورما لحمة", "930 سعر للصغير"), "", None, "fatteh meat", []),
    ("sh-kilo", "كيلو شاورما فراخ للعيلة", "shawarma", 52000, [], food_specs("كيلو شاورما، ٨ عيش سوري، ثومية كبيرة، مخلل، بطاطس عائلي", "-", serves="٤ - ٥ أشخاص"), "", 58000, "shawarma meat|doner kebab|gyro meat", ["عائلي"]),
    # fried chicken
    ("fc-pieces", "فراخ بروستد مقرمشة", "fried-chicken", 9500, [("العدد", [("٢ قطعة", 0), ("٤ قطع", 8000), ("٨ قطع", 23500)]), SPICE], food_specs("فراخ متبلة بخلطة البيت ومقلية مقرمشة", "480 سعر لقطعتين", spicy="عادي أو حار"), "BEST_SELLER", None, "fried chicken", ["الأكثر طلباً"]),
    ("fc-strips", "استربس فراخ", "fried-chicken", 9000, [("العدد", [("٣ قطع", 0), ("٥ قطع", 5000), ("٩ قطع", 15000)]), SPICE], food_specs("شرائح صدور فراخ مقرمشة مع صوص", "420 سعر لـ٣ قطع", spicy="عادي أو حار"), "", None, "chicken strips", []),
    ("fc-wings", "وينجز مقرمشة", "fried-chicken", 11000, [("العدد", [("٦ قطع", 0), ("١٢ قطعة", 9000)]), ("الصوص", [("باربكيو", 0), ("بافلو حار", 0), ("عسل وخردل", 0)])], food_specs("أجنحة فراخ مقرمشة متغطية بالصوص", "560 سعر لـ٦ قطع", spicy="حسب الصوص"), "", None, "chicken wings", []),
    ("fc-nuggets", "ناجتس فراخ", "fried-chicken", 7000, [("العدد", [("٦ قطع", 0), ("٩ قطع", 3000), ("٢٠ قطعة", 13000)])], food_specs("قطع صدور فراخ مقرمشة مع كاتشب", "290 سعر لـ٦ قطع", spicy="مش حار"), "", None, "chicken nuggets", ["للأطفال"]),
    ("fc-popcorn", "بوب كورن تشيكن", "fried-chicken", 6500, [SMALL_BIG(3000), SPICE], food_specs("قطع فراخ صغيرة مقرمشة", "380 سعر للصغير", spicy="عادي أو حار"), "", None, "popcorn chicken", []),
    ("fc-bucket", "بوكس العيلة بروستد ١٢ قطعة", "fried-chicken", 48000, [SPICE], food_specs("١٢ قطعة بروستد، بطاطس عائلي، كول سلو كبير، ٤ عيش، ثومية", "-", spicy="عادي أو حار", serves="٤ - ٦ أشخاص"), "BEST_SELLER", 55000, "fried chicken bucket|fried chicken|fried chicken plate", ["عائلي"]),
    # sandwiches and burgers
    ("bg-classic", "برجر كلاسيك", "sandwiches-burgers", 12000, [("الحجم", [("سنجل", 0), ("دبل", 5500)])], food_specs("لحم بقري ١٥٠ جم، خس، طماطم، بصل، صوص البيت", "650 سعر للسنجل"), "", None, "hamburger", []),
    ("bg-cheese", "تشيز برجر", "sandwiches-burgers", 13500, [("الحجم", [("سنجل", 0), ("دبل", 5500)])], food_specs("لحم بقري، جبنة شيدر، مخلل، صوص", "720 سعر للسنجل"), "BEST_SELLER", None, "cheeseburger", []),
    ("bg-smoky", "سموكي برجر", "sandwiches-burgers", 15500, [("الحجم", [("سنجل", 0), ("دبل", 6000)])], food_specs("لحم بقري، بيكون بقري مدخن، شيدر، بصل مكرمل، صوص باربكيو", "820 سعر للسنجل"), "", None, "bacon burger", []),
    ("bg-mushroom", "ماشروم برجر", "sandwiches-burgers", 15000, [("الحجم", [("سنجل", 0), ("دبل", 5500)])], food_specs("لحم بقري، مشروم سوتيه، صوص كريمة، سويسري", "790 سعر للسنجل"), "", None, "mushroom swiss burger", []),
    ("sw-zinger", "ساندوتش زنجر", "sandwiches-burgers", 9500, [SPICE], food_specs("صدر فراخ مقرمش، خس، مايونيز", "560 سعر", spicy="عادي أو حار"), "BEST_SELLER", None, "chicken sandwich crispy", []),
    ("sw-crispy-cheese", "ساندوتش كرسبي بالجبنة", "sandwiches-burgers", 10500, [], food_specs("فراخ كرسبي، شيدر، هالبينو، صوص رانش", "610 سعر", spicy="متوسط"), "", None, "chicken burger|crispy chicken sandwich|chicken sandwich", []),
    ("sw-kofta", "ساندوتش كفتة", "sandwiches-burgers", 7000, [("الخبز", [("عيش بلدي", 0), ("فينو", 0)])], food_specs("كفتة مشوية، طحينة، بقدونس، بصل", "450 سعر"), "", None, "kofta sandwich|kofta|kofta bread", []),
    ("sw-tawook", "ساندوتش شيش طاووق", "sandwiches-burgers", 9000, [("الخبز", [("صاج", 0), ("فينو", 0)])], food_specs("طاووق مشوي، ثومية، مخلل، بطاطس", "520 سعر"), "", None, "shish taouk|taouk|chicken wrap", []),
    ("sw-falafel", "ساندوتش فلافل سوري", "sandwiches-burgers", 3500, [], food_specs("فلافل، طحينة، خضار، مخلل", "350 سعر", spicy="مش حار"), "", None, "falafel sandwich", ["نباتي"]),
    ("sw-hotdog", "هوت دوج", "sandwiches-burgers", 7500, [], food_specs("سجق بقري، صوص خردل وكاتشب، بصل مقرمش", "480 سعر"), "", None, "hot dog bun|hot dog|hotdog", []),
    # grills
    ("gr-kofta", "كفتة مشوية على الفحم", "grills", 19000, [WEIGHT3(17000, 51000)], food_specs("لحم بقري مفروم بالبهارات، مع أرز وسلطة وطحينة وعيش", "-", serves="ربع كيلو لشخص"), "BEST_SELLER", None, "kofta grilled", []),
    ("gr-tawook", "شيش طاووق مشوي", "grills", 17000, [WEIGHT3(15000, 45000)], food_specs("صدور فراخ متبلة بالزبادي، مع أرز وثومية وعيش", "-", serves="ربع كيلو لشخص"), "", None, "shish tawook skewers", []),
    ("gr-ribs", "ريش ضاني مشوية", "grills", 36000, [WEIGHT2(34000)], food_specs("ريش ضاني بلدي، مع أرز وسلطة", "-"), "", None, "lamb chops grilled", ["بريميوم"]),
    ("gr-chicken", "فراخ مشوية على الفحم", "grills", 11000, [("الكمية", [("ربع فرخة", 0), ("نص فرخة", 9000), ("فرخة كاملة", 27000)]), ("التتبيلة", [("كلاسيك", 0), ("ليمون وتوم", 0), ("حار", 0)])], food_specs("فراخ متبلة مع أرز وثومية وعيش", "-", spicy="حسب التتبيلة"), "", None, "grilled chicken charcoal", []),
    ("gr-kebab", "كباب مشوي", "grills", 26000, [WEIGHT2(24000)], food_specs("قطع لحم بتلو مشوية، مع أرز وسلطة", "-"), "", None, "kebab grilled meat", []),
    ("gr-mix", "مشكل مشويات", "grills", 32000, [("الحجم", [("فرد", 0), ("عائلي", 83000)])], food_specs("كفتة، طاووق، كباب، ريش، أرز، سلطات، عيش", "-", serves="الفرد لشخص، العائلي ٤-٥"), "", None, "mixed grill platter|mixed grill|kebab platter", ["عائلي"]),
    # meals and combos
    ("ml-shawarma-ch", "وجبة ساندوتش شاورما فراخ", "meals-combos", 14500, [("الحجم", [("عادي", 0), ("كبير", 3000)]), DRINK], food_specs("ساندوتش شاورما فراخ، بطاطس، مشروب كانز", "-"), "BEST_SELLER", 16500, "shawarma fries|shawarma meal|shawarma", ["عرض"]),
    ("ml-shawarma-meat", "وجبة ساندوتش شاورما لحمة", "meals-combos", 17000, [("الحجم", [("عادي", 0), ("كبير", 3500)]), DRINK], food_specs("ساندوتش شاورما لحمة، بطاطس، مشروب كانز", "-"), "", 19500, "shawarma sandwich|shawarma|kebab sandwich", ["عرض"]),
    ("ml-broasted", "وجبة بروستد ٤ قطع", "meals-combos", 23500, [SPICE, DRINK], food_specs("٤ قطع بروستد، بطاطس، كول سلو، عيش، ثومية، مشروب", "-", spicy="عادي أو حار"), "", 27000, "fried chicken meal", ["عرض"]),
    ("ml-burger", "وجبة برجر كلاسيك", "meals-combos", 17500, [DRINK], food_specs("برجر كلاسيك، بطاطس، مشروب كانز", "-"), "", 19500, "burger fries|hamburger fries|cheeseburger", ["عرض"]),
    ("ml-friends", "وجبة الأصحاب", "meals-combos", 52000, [], food_specs("٤ ساندوتش شاورما فراخ، ٢ بطاطس كبير، ٤ مشروب", "-", serves="٤ أشخاص"), "BEST_SELLER", 60000, "shawarma sandwiches|shawarma|sandwiches platter", ["عرض", "عائلي"]),
    ("ml-family-grill", "وجبة العيلة مشويات", "meals-combos", 79000, [], food_specs("نص كيلو كفتة، نص كيلو طاووق، أرز، ٣ سلطات، عيش", "-", serves="٤ أشخاص"), "", 89000, "grill platter|kebab platter|mixed grill", ["عرض", "عائلي"]),
    ("ml-kids", "وجبة أطفال", "meals-combos", 9500, [("العصير", [("تفاح", 0), ("مانجو", 0)])], food_specs("٣ ناجتس، بطاطس صغير، عصير، لعبة", "-", spicy="مش حار"), "", None, "chicken nuggets fries|nuggets|chicken nuggets", ["للأطفال"]),
    # sides
    ("sd-fries", "بطاطس مقلية", "appetizers-sides", 3000, [("الحجم", [("صغير", 0), ("وسط", 1500), ("كبير", 3000), ("عائلي", 6000)])], food_specs("بطاطس طازة مقلية", "230 سعر للصغير", spicy="مش حار"), "", None, "french fries plate|french fries|chips potato", ["نباتي"]),
    ("sd-cheese-fries", "بطاطس بالجبنة الشيدر", "appetizers-sides", 6500, [SMALL_BIG(2000)], food_specs("بطاطس، صوص شيدر، هالبينو اختياري في الملاحظة", "420 سعر للصغير"), "", None, "cheese fries", []),
    ("sd-garlic", "ثومية", "appetizers-sides", 1000, [SMALL_BIG(1500)], food_specs("ثوم، زيت، ليمون", "-", spicy="مش حار"), "", None, "toum garlic sauce", ["نباتي"]),
    ("sd-hummus", "حمص بالطحينة", "appetizers-sides", 4000, [], food_specs("حمص، طحينة، زيت زيتون، ليمون", "320 سعر", spicy="مش حار"), "", None, "hummus plate|hummus bowl|hummus", ["نباتي"]),
    ("sd-babaghanoush", "بابا غنوج", "appetizers-sides", 4000, [], food_specs("باذنجان مشوي، طحينة، رمان", "260 سعر", spicy="مش حار"), "", None, "baba ghanoush", ["نباتي"]),
    ("sd-coleslaw", "كول سلو", "appetizers-sides", 2500, [SMALL_BIG(2000)], food_specs("كرنب، جزر، مايونيز", "180 سعر للصغير", spicy="مش حار"), "", None, "coleslaw", ["نباتي"]),
    ("sd-fattoush", "سلطة فتوش", "appetizers-sides", 5500, [], food_specs("خس، طماطم، خيار، عيش محمص، دبس رمان", "210 سعر", spicy="مش حار"), "", None, "fattoush salad", ["نباتي"]),
    ("sd-mozzarella", "أصابع موتزاريلا", "appetizers-sides", 9000, [], food_specs("٥ أصابع موتزاريلا مقرمشة مع صوص", "480 سعر", spicy="مش حار"), "", None, "mozzarella sticks", []),
    ("sd-rice", "أرز بسمتي", "appetizers-sides", 4000, [], food_specs("أرز بسمتي بالشعرية والمكسرات", "350 سعر", spicy="مش حار"), "", None, "basmati rice plate|rice dish|pilaf", []),
    # drinks
    ("dr-soda", "مشروب غازي", "soft-drinks", 2500, [("النوع", [("بيبسي", 0), ("سفن أب", 0), ("ميرندا", 0), ("ماونتن ديو", 0)]), ("الحجم", [("كانز", 0), ("١ لتر", 1500)])], [{"k": "الحجم", "v": "كانز ٣٣٠ مل أو ١ لتر"}], "", None, "soft drink glass ice|cola glass|soda glass", []),
    ("dr-water", "مياه معدنية", "soft-drinks", 1000, [("الحجم", [("صغيرة ٦٠٠ مل", 0), ("كبيرة ١.٥ لتر", 500)])], [], "", None, "water bottle|mineral water bottle|bottled water", []),
    ("dr-lemon-mint", "ليمون بالنعناع", "soft-drinks", 4500, [], [{"k": "المكونات", "v": "ليمون طازة، نعناع، سكر"}], "", None, "lemon mint juice", ["فريش"]),
    ("dr-mango", "عصير مانجو فريش", "soft-drinks", 5500, [], [{"k": "المكونات", "v": "مانجو طازة"}], "", None, "mango juice", ["فريش"]),
    ("dr-orange", "عصير برتقال فريش", "soft-drinks", 4500, [], [{"k": "المكونات", "v": "برتقال طازة معصور"}], "", None, "orange juice|orange juice glass|juice glass", ["فريش"]),
    ("dr-icetea", "آيس تي خوخ", "soft-drinks", 4000, [], [{"k": "المكونات", "v": "شاي، خوخ، ثلج"}], "", None, "iced tea glass|iced tea|ice tea", []),
    # desserts
    ("ds-omali", "أم علي", "restaurant-desserts", 7000, [], food_specs("رقائق، لبن، قشطة، مكسرات، زبيب", "520 سعر", spicy="-"), "BEST_SELLER", None, "om ali dessert", []),
    ("ds-kunafa", "كنافة بالقشطة", "restaurant-desserts", 7500, [], food_specs("كنافة، قشطة، شربات", "560 سعر", spicy="-"), "", None, "kunafa", []),
    ("ds-roz", "رز بلبن", "restaurant-desserts", 3500, [("الإضافة", [("سادة", 0), ("بالمكسرات", 1500)])], food_specs("أرز، لبن، سكر، فانيليا", "300 سعر", spicy="-"), "", None, "sütlaç|rice pudding bowl|muhallebi", []),
    ("ds-brownies", "براونيز بالشوكولاتة", "restaurant-desserts", 6000, [], food_specs("شوكولاتة داكنة، زبدة، بندق", "450 سعر", spicy="-"), "", None, "chocolate brownie", []),
    ("ds-cheesecake", "تشيز كيك فراولة", "restaurant-desserts", 8500, [], food_specs("جبنة كريمي، بسكويت، صوص فراولة", "480 سعر", spicy="-"), "NEW", None, "strawberry cheesecake slice|cheesecake slice|cheesecake", []),
    ("ds-basbousa", "بسبوسة بالقشطة", "restaurant-desserts", 5000, [], food_specs("سميد، قشطة، شربات، جوز هند", "430 سعر", spicy="-"), "", None, "basbousa|basbusa|revani", []),
]

RESTAURANT_PROFILE = {
    "storeName": "مطعم بيت الشاورما",
    "shortDescription": "شاورما سوري، بروستد ومشويات على الفحم · تيك أواي وتوصيل في القاهرة والجيزة",
    "about": "بيت الشاورما مطعم تيك أواي في مدينة نصر من ٢٠١٢. بنعمل الشاورما على السيخ قدامك كل يوم، والبروستد بخلطة البيت، والمشويات على الفحم. كل الأكل بيتعمل لما تطلب.",
    "announcement": "🔥 كود SHAWARMA15 خصم ١٥٪ على الطلبات من ٣٠٠ جنيه · توصيل مجاني من ٤٠٠ جنيه",
    "shippingPolicy": "بنوصل داخل القاهرة والجيزة (مدينة نصر، مصر الجديدة، التجمع، المعادي، الدقي، المهندسين، الهرم، فيصل، ٦ أكتوبر، الشيخ زايد). رسوم التوصيل ٢٥ جنيه، ومجاني للطلبات من ٤٠٠ جنيه. التحضير ٢٠ - ٣٠ دقيقة، والتوصيل ٣٠ - ٥٠ دقيقة حسب المنطقة. الاستلام من الفرع من غير رسوم. أقل طلب ١٠٠ جنيه.",
    "returnPolicy": "الأكل مش بيترجع بعد الاستلام لأسباب صحية. لو في غلط في الطلب أو صنف ناقص أو مش مظبوط كلمنا خلال ساعة من الاستلام وهنبدله أو نرجعلك تمنه. إلغاء الطلب متاح قبل ما نبدأ التحضير بس.",
    "supportPhone": "01001234567", "whatsapp": "01001234567", "addressText": "١٥ شارع عباس العقاد، مدينة نصر، القاهرة",
    "mapsUrl": "https://maps.google.com/?q=Abbas+El+Akkad+Nasr+City",
    "returnWindowDays": 0, "minOrderMinor": 10000, "vatIncluded": True, "vatPercent": 14,
}

# ---------------------------------------------------------------- fashion
AP = ("APPAREL", ["S", "M", "L", "XL", "XXL"])
APW = ("APPAREL", ["S", "M", "L", "XL"])
KID = ("KIDS", ["2 سنة", "4 سنة", "6 سنة", "8 سنة", "10 سنة"])
KIDB = ("KIDS", ["4 سنة", "6 سنة", "8 سنة", "10 سنة", "12 سنة"])
BABY = ("KIDS", ["0-3 شهور", "3-6 شهور", "6-12 شهر", "12-18 شهر"])
SHOE_M = ("SHOE", ["40", "41", "42", "43", "44", "45"])
SHOE_W = ("SHOE", ["36", "37", "38", "39", "40", "41"])
SHOE_K = ("SHOE", ["26", "27", "28", "29", "30", "31", "32"])
FREE = ("APPAREL", ["فري سايز"])

FASHION = [
    # men: shirts and t-shirts
    ("m-oxford", "قميص أكسفورد قطن", "shirts-tops", "MEN", ["أبيض", "سماوي", "كحلي", "وردي"], AP, 65000, 52000, "oxford shirt", "", None),
    ("m-linen", "قميص كتان بكم طويل", "shirts-tops", "MEN", ["أبيض", "بيج", "زيتي"], AP, 72000, None, "linen shirt", "NEW", None),
    ("m-denim-shirt", "قميص جينز رجالي", "shirts-tops", "MEN", ["أزرق", "كحلي"], AP, 69000, None, "denim shirt", "", None),
    ("m-flannel", "قميص كاروهات فلانل", "shirts-tops", "MEN", ["أحمر", "أخضر", "رمادي"], AP, 62000, 49900, "flannel shirt", "SALE", None),
    ("m-dress-shirt", "قميص رسمي سليم فيت", "shirts-tops", "MEN", ["أبيض", "سماوي", "أسود"], AP, 75000, None, "dress shirt white", "", None),
    ("m-short-sleeve", "قميص نص كم صيفي", "shirts-tops", "MEN", ["أبيض", "بيج", "سماوي"], AP, 48000, None, "short sleeve shirt", "", None),
    ("m-basic-tee", "تيشيرت قطن سادة رجالي", "shirts-tops", "MEN", ["أسود", "أبيض", "رمادي", "كحلي", "زيتي"], AP, 29000, None, "plain t-shirt", "BEST_SELLER", None),
    ("m-polo", "تيشيرت بولو بيكيه", "shirts-tops", "MEN", ["أبيض", "كحلي", "أحمر", "أخضر"], AP, 42000, 35000, "polo shirt", "SALE", None),
    ("m-oversized", "تيشيرت أوفر سايز مطبوع", "shirts-tops", "MEN", ["أسود", "أبيض", "بيج"], AP, 36000, None, "graphic t-shirt", "NEW", None),
    ("m-henley", "تيشيرت هنلي بكم طويل", "shirts-tops", "MEN", ["رمادي", "كحلي", "بني"], AP, 38000, None, "henley shirt", "", None),
    ("m-vneck", "تيشيرت رقبة V", "shirts-tops", "MEN", ["أبيض", "أسود", "رمادي"], AP, 27000, None, "v-neck t-shirt", "", None),
    # men: sport
    ("m-dryfit", "تيشيرت رياضي دراي فيت", "activewear", "MEN", ["أسود", "أزرق", "رمادي"], AP, 34000, None, "sports t-shirt running", "", None),
    ("m-tracksuit", "ترينج رياضي رجالي", "activewear", "MEN", ["أسود", "كحلي", "رمادي"], AP, 115000, 95000, "tracksuit", "SALE", None),
    ("m-run-shorts", "شورت رياضي رجالي", "activewear", "MEN", ["أسود", "كحلي", "رمادي"], AP, 26000, None, "running shorts", "", None),
    ("m-football", "تيشيرت كرة قدم", "activewear", "MEN", ["أحمر", "أبيض", "أزرق"], AP, 45000, None, "football jersey", "", None),
    # men: pants
    ("m-slim-jeans", "جينز سليم فيت رجالي", "pants-jeans", "MEN", ["أزرق", "كحلي", "أسود"], AP, 89000, None, "jeans trousers|denim jeans|jeans", "BEST_SELLER", None),
    ("m-regular-jeans", "جينز ريجولار فيت", "pants-jeans", "MEN", ["أزرق", "سماوي"], AP, 85000, None, "blue jeans", "", None),
    ("m-ripped-jeans", "جينز سكيني ممزق", "pants-jeans", "MEN", ["أزرق", "أسود"], AP, 92000, 78000, "ripped jeans", "SALE", None),
    ("m-chino", "بنطلون تشينو", "pants-jeans", "MEN", ["بيج", "كحلي", "زيتي", "أسود"], AP, 69000, None, "chino pants", "", None),
    ("m-cargo", "بنطلون كارجو", "pants-jeans", "MEN", ["زيتي", "بيج", "أسود"], AP, 78000, None, "cargo pants", "NEW", None),
    ("m-trousers", "بنطلون قماش رسمي", "pants-jeans", "MEN", ["أسود", "رمادي", "كحلي"], AP, 82000, None, "dress trousers", "", None),
    ("m-denim-shorts", "شورت جينز رجالي", "pants-jeans", "MEN", ["أزرق", "سماوي"], AP, 45000, None, "denim shorts", "", None),
    ("m-jogger", "بنطلون جوجر قطن", "pants-jeans", "MEN", ["رمادي", "أسود", "كحلي"], AP, 48000, None, "sweatpants|jogging pants|track pants", "", None),
    # men: jackets, suits, knitwear
    ("m-denim-jacket", "جاكيت جينز رجالي", "jackets-coats", "MEN", ["أزرق", "أسود"], AP, 125000, None, "denim jacket", "", None),
    ("m-leather-jacket", "جاكيت جلد صناعي", "jackets-coats", "MEN", ["أسود", "بني"], AP, 195000, 165000, "leather jacket", "SALE", None),
    ("m-puffer", "جاكيت بافر شتوي رجالي", "jackets-coats", "MEN", ["أسود", "كحلي", "زيتي"], AP, 185000, None, "puffer jacket", "NEW", None),
    ("m-bomber", "بومبر جاكيت", "jackets-coats", "MEN", ["زيتي", "أسود", "كحلي"], AP, 135000, None, "bomber jacket", "", None),
    ("m-overcoat", "بالطو صوف رجالي", "jackets-coats", "MEN", ["رمادي", "بيج", "أسود"], AP, 265000, None, "wool overcoat", "", None),
    ("m-fleece", "جاكيت فليس رجالي", "jackets-coats", "MEN", ["رمادي", "كحلي", "أسود"], AP, 89000, None, "fleece jacket", "", None),
    ("m-blazer", "بليزر كاجوال رجالي", "suits-blazers", "MEN", ["كحلي", "رمادي", "بيج"], AP, 175000, None, "blazer jacket", "", None),
    ("m-suit", "بدلة رجالي كلاسيك", "suits-blazers", "MEN", ["أسود", "كحلي", "رمادي"], AP, 395000, 345000, "men's suit", "SALE", None),
    ("m-hoodie", "هودي قطن بكابيشون", "knitwear-hoodies", "MEN", ["أسود", "رمادي", "كحلي", "بيج"], AP, 69000, None, "hoodie", "BEST_SELLER", None),
    ("m-sweatshirt", "سويت شيرت رقبة دائرية", "knitwear-hoodies", "MEN", ["رمادي", "أسود", "زيتي"], AP, 59000, None, "sweatshirt", "", None),
    ("m-turtleneck", "بلوفر صوف نص رقبة", "knitwear-hoodies", "MEN", ["بني", "كحلي", "بيج"], AP, 78000, None, "turtleneck sweater", "", None),
    ("m-cardigan", "كارديجان رجالي تريكو", "knitwear-hoodies", "MEN", ["رمادي", "كحلي"], AP, 75000, None, "cardigan sweater", "", None),
    ("m-galabeya", "جلابية رجالي قطن", "traditional-wear", "MEN", ["أبيض", "بيج", "رمادي", "كحلي"], ("APPAREL", ["M", "L", "XL", "XXL", "3XL"]), 65000, None, "thobe", "", None),
    ("m-pajamas", "بيجامة رجالي قطن", "sleepwear", "MEN", ["رمادي", "كحلي"], AP, 56000, None, "pajamas", "", None),
    ("m-swim", "مايوه شورت رجالي", "swimwear", "MEN", ["أزرق", "أسود", "أحمر"], AP, 39000, None, "swim trunks|boardshorts|swimming trunks", "", None),
    ("m-underwear", "طقم بوكسر قطن ٣ قطع", "underwear-socks", "MEN", ["متعدد"], AP, 39000, None, "boxer briefs", "", None),
    ("m-socks", "شراب قطن ٦ أزواج", "underwear-socks", "MEN", ["متعدد"], FREE, 22000, None, "socks", "", None),
    # women: dresses
    ("w-midi-floral", "فستان ميدي مشجر", "dresses", "WOMEN", ["متعدد", "أزرق", "وردي"], APW, 98000, None, "floral dress", "", None),
    ("w-satin-evening", "فستان سهرة ستان", "dresses", "WOMEN", ["أسود", "كحلي", "أحمر", "ذهبي"], APW, 185000, 155000, "satin evening gown", "BEST_SELLER", None),
    ("w-casual-dress", "فستان كاجوال قطن", "dresses", "WOMEN", ["بيج", "أسود", "زيتي"], APW, 75000, None, "casual cotton dress", "", None),
    ("w-maxi", "فستان ماكسي شيفون", "dresses", "WOMEN", ["وردي", "سماوي", "بنفسجي"], APW, 125000, None, "maxi dress", "", None),
    ("w-linen-dress", "فستان صيفي كتان", "dresses", "WOMEN", ["أبيض", "بيج", "أصفر"], APW, 89000, 72000, "linen dress", "SALE", None),
    ("w-knit-dress", "فستان تريكو شتوي", "dresses", "WOMEN", ["بني", "رمادي", "أسود"], APW, 115000, None, "sweater dress", "NEW", None),
    ("w-puff-dress", "فستان قصير بكم منفوخ", "dresses", "WOMEN", ["أسود", "أبيض", "أحمر"], APW, 85000, None, "puff sleeve dress", "", None),
    ("w-sequin", "فستان سواريه مطرز", "bridal-evening", "WOMEN", ["ذهبي", "فضي", "كحلي"], APW, 325000, None, "sequin dress", "LIMITED", None),
    # women: tops
    ("w-chiffon", "بلوزة شيفون", "shirts-tops", "WOMEN", ["أبيض", "وردي", "أسود", "سماوي"], APW, 52000, None, "chiffon blouse", "", None),
    ("w-satin-blouse", "بلوزة ستان", "shirts-tops", "WOMEN", ["بيج", "أسود", "أخضر"], APW, 56000, None, "satin blouse", "", None),
    ("w-oversized-shirt", "قميص قطن واسع نسائي", "shirts-tops", "WOMEN", ["أبيض", "سماوي", "بيج"], APW, 59000, None, "women's shirt", "", None),
    ("w-basic-tee", "تيشيرت قطن بيسك نسائي", "shirts-tops", "WOMEN", ["أبيض", "أسود", "وردي", "رمادي"], APW, 25000, None, "women's t-shirt", "BEST_SELLER", None),
    ("w-crochet", "توب كروشيه", "shirts-tops", "WOMEN", ["أبيض", "بيج"], APW, 45000, None, "crochet top", "NEW", None),
    ("w-puff-blouse", "بلوزة بكم منفوخ", "shirts-tops", "WOMEN", ["أبيض", "أسود", "وردي"], APW, 54000, None, "puff sleeve blouse", "", None),
    ("w-long-sleeve", "توب قطن بكم طويل", "shirts-tops", "WOMEN", ["أسود", "أبيض", "بيج"], APW, 32000, None, "long sleeve top", "", None),
    # women: pants and skirts
    ("w-mom-jeans", "جينز مام فيت", "pants-jeans", "WOMEN", ["أزرق", "سماوي", "أسود"], APW, 85000, None, "mom jeans", "BEST_SELLER", None),
    ("w-wide-jeans", "جينز وايد ليج", "pants-jeans", "WOMEN", ["أزرق", "سماوي"], APW, 89000, 75000, "wide leg jeans", "SALE", None),
    ("w-wide-trousers", "بنطلون قماش واسع", "pants-jeans", "WOMEN", ["أسود", "بيج", "رمادي"], APW, 69000, None, "wide leg trousers", "", None),
    ("w-palazzo", "بنطلون بلاتزو", "pants-jeans", "WOMEN", ["أسود", "كحلي", "بيج"], APW, 62000, None, "palazzo pants", "", None),
    ("w-leggings", "ليجن قطن", "pants-jeans", "WOMEN", ["أسود", "رمادي", "كحلي"], APW, 22000, None, "leggings", "", None),
    ("w-skinny", "جينز سكيني هاي ويست", "pants-jeans", "WOMEN", ["أزرق", "أسود"], APW, 82000, None, "skinny jeans women", "", None),
    ("w-pleated", "جيبة بليسيه ميدي", "skirts", "WOMEN", ["بيج", "أسود", "زيتي", "وردي"], APW, 65000, None, "pleated skirt", "", None),
    ("w-denim-skirt", "جيبة جينز طويلة", "skirts", "WOMEN", ["أزرق", "سماوي"], APW, 72000, None, "denim skirt", "", None),
    ("w-satin-skirt", "جيبة ستان ميدي", "skirts", "WOMEN", ["بيج", "أسود", "أخضر"], APW, 69000, None, "satin skirt", "", None),
    # women: modest
    ("w-abaya", "عباية كريب سادة", "abayas-modest", "WOMEN", ["أسود", "كحلي", "بيج"], ("APPAREL", ["M", "L", "XL", "XXL"]), 120000, None, "abaya", "BEST_SELLER", None),
    ("w-abaya-emb", "عباية مطرزة", "abayas-modest", "WOMEN", ["أسود", "كحلي"], ("APPAREL", ["M", "L", "XL", "XXL"]), 165000, 140000, "abaya|kaftan|jalabiya", "SALE", None),
    ("w-isdal", "إسدال صلاة", "abayas-modest", "WOMEN", ["أسود", "بيج", "وردي", "سماوي"], FREE, 45000, None, "prayer clothes", "", None),
    ("w-modest-set", "طقم محجبات كارديجان وبنطلون", "abayas-modest", "WOMEN", ["بيج", "زيتي", "أسود"], APW, 135000, None, "modest fashion", "NEW", None),
    ("w-modest-dress", "فستان محجبات طويل", "abayas-modest", "WOMEN", ["كحلي", "زيتي", "بني"], APW, 115000, None, "modest dress long", "", None),
    ("w-hijab-chiffon", "طرحة شيفون", "scarves-hijabs", "WOMEN", ["أسود", "بيج", "وردي", "كحلي", "أبيض"], None, 12000, None, "hijab", "", None),
    ("w-hijab-jersey", "طرحة جيرسيه", "scarves-hijabs", "WOMEN", ["أسود", "رمادي", "بني", "بيج"], None, 9500, None, "headscarf", "", None),
    ("w-burkini", "مايوه شرعي بوركيني", "swimwear", "WOMEN", ["أسود", "كحلي", "تركواز"], APW, 115000, None, "burkini", "", None),
    # women: outerwear, knit, home
    ("w-cardigan", "كارديجان تريكو نسائي", "knitwear-hoodies", "WOMEN", ["بيج", "رمادي", "وردي"], APW, 69000, None, "women's cardigan", "", None),
    ("w-blazer", "بليزر نسائي", "suits-blazers", "WOMEN", ["أسود", "بيج", "وردي"], APW, 145000, None, "women's blazer", "", None),
    ("w-denim-jacket", "جاكيت جينز نسائي", "jackets-coats", "WOMEN", ["أزرق", "سماوي"], APW, 115000, None, "women's denim jacket", "", None),
    ("w-coat", "بالطو صوف نسائي", "jackets-coats", "WOMEN", ["بيج", "أسود", "رمادي"], APW, 245000, 210000, "women's coat", "SALE", None),
    ("w-puffer", "جاكيت بافر نسائي", "jackets-coats", "WOMEN", ["أسود", "بيج", "وردي"], APW, 175000, None, "women's puffer jacket", "NEW", None),
    ("w-hoodie", "هودي نسائي", "knitwear-hoodies", "WOMEN", ["رمادي", "وردي", "أسود"], APW, 65000, None, "women's hoodie", "", None),
    ("w-sweater", "بلوفر تريكو نسائي", "knitwear-hoodies", "WOMEN", ["بيج", "أبيض", "بني"], APW, 72000, None, "knit sweater", "", None),
    ("w-pajamas", "بيجامة قطن نسائي", "sleepwear", "WOMEN", ["وردي", "سماوي", "رمادي"], APW, 59000, None, "women's pajamas", "", None),
    ("w-robe", "روب ستان", "sleepwear", "WOMEN", ["أسود", "وردي", "بيج"], APW, 52000, None, "satin robe", "", None),
    ("w-sport-set", "طقم رياضي نسائي", "activewear", "WOMEN", ["أسود", "وردي", "رمادي"], APW, 89000, None, "women's sportswear", "", None),
    # kids
    ("k-boy-set", "طقم ولادي تيشيرت وشورت", "outfit-sets", "BOYS", ["أزرق", "أخضر", "رمادي"], KID, 42000, None, "boy's clothing", "", None),
    ("k-girl-set", "طقم بناتي بلوزة وجيبة", "outfit-sets", "GIRLS", ["وردي", "أبيض", "بنفسجي"], KID, 48000, None, "girl's clothing", "", None),
    ("k-tracksuit", "ترينج أطفال", "outfit-sets", "BOYS", ["كحلي", "رمادي", "أحمر"], KIDB, 52000, 42000, "kids tracksuit", "SALE", None),
    ("k-boy-tee", "تيشيرت ولادي مطبوع", "shirts-tops", "BOYS", ["أبيض", "أزرق", "أصفر"], KIDB, 19000, None, "kids t-shirt", "BEST_SELLER", None),
    ("k-girl-tee", "تيشيرت بناتي", "shirts-tops", "GIRLS", ["وردي", "أبيض", "أصفر"], KIDB, 18000, None, "girls t-shirt", "", None),
    ("k-boy-shirt", "قميص ولادي كاروهات", "shirts-tops", "BOYS", ["أزرق", "أحمر"], KIDB, 32000, None, "boys plaid shirt", "", None),
    ("k-boy-jeans", "جينز ولادي", "pants-jeans", "BOYS", ["أزرق", "أسود"], KIDB, 39000, 32000, "kids jeans", "SALE", None),
    ("k-girl-leggings", "ليجن بناتي", "pants-jeans", "GIRLS", ["أسود", "وردي", "رمادي"], KIDB, 15000, None, "girls leggings", "", None),
    ("k-shorts", "شورت أطفال قطن", "pants-jeans", "BOYS", ["كحلي", "بيج", "رمادي"], KID, 17000, None, "kids shorts", "", None),
    ("k-tutu", "فستان بناتي منفوش", "dresses", "GIRLS", ["وردي", "أبيض", "أحمر"], KID, 69000, None, "tutu dress", "BEST_SELLER", None),
    ("k-cotton-dress", "فستان بناتي قطن", "dresses", "GIRLS", ["أصفر", "سماوي", "وردي"], KID, 42000, None, "girls cotton dress", "", None),
    ("k-party-dress", "فستان بناتي سهرة", "dresses", "GIRLS", ["أبيض", "ذهبي", "وردي"], KIDB, 95000, None, "girl dress|child dress|children dress", "", None),
    ("k-tulle-skirt", "جيبة تول بناتي", "skirts", "GIRLS", ["وردي", "بنفسجي", "أبيض"], KID, 38000, None, "tulle skirt", "", None),
    ("k-boy-puffer", "جاكيت بافر ولادي", "jackets-coats", "BOYS", ["أحمر", "كحلي", "أسود"], KIDB, 89000, None, "kids winter jacket", "NEW", None),
    ("k-girl-puffer", "جاكيت بافر بناتي", "jackets-coats", "GIRLS", ["وردي", "بنفسجي"], KIDB, 89000, None, "girls winter coat", "NEW", None),
    ("k-hoodie", "هودي أطفال", "knitwear-hoodies", "BOYS", ["رمادي", "كحلي", "أخضر"], KIDB, 39000, None, "kids hoodie", "", None),
    ("k-boy-suit", "بدلة أطفال رسمي", "suits-blazers", "BOYS", ["كحلي", "أسود"], KIDB, 125000, None, "boys suit", "", None),
    ("k-boy-pajamas", "بيجامة ولادي", "sleepwear", "BOYS", ["أزرق", "رمادي"], KID, 29000, None, "children's pajamas", "", None),
    ("k-girl-pajamas", "بيجامة بناتي", "sleepwear", "GIRLS", ["وردي", "بنفسجي"], KID, 29000, None, "girls pajamas", "", None),
    ("k-baby-romper", "أوفرول بيبي قطن", "baby-clothing", "BABY", ["أبيض", "سماوي", "وردي", "أصفر"], BABY, 26000, None, "baby romper", "BEST_SELLER", None),
    ("k-baby-set", "طقم بيبي ٣ قطع", "baby-clothing", "BABY", ["أبيض", "سماوي", "وردي"], BABY, 38000, None, "baby clothes", "", None),
    # shoes
    ("s-m-sneakers", "سنيكرز جلد أبيض رجالي", "casual-shoes", "MEN", ["أبيض", "أسود"], SHOE_M, 125000, None, "white sneakers", "BEST_SELLER", None),
    ("s-m-running", "حذاء رياضي للجري رجالي", "sport-shoes", "MEN", ["أسود", "رمادي", "أزرق"], SHOE_M, 145000, 119000, "running shoes", "SALE", None),
    ("s-m-oxford", "حذاء كلاسيك جلد طبيعي", "formal-shoes", "MEN", ["أسود", "بني"], SHOE_M, 165000, None, "oxford shoes", "", None),
    ("s-m-loafer", "لوفر سويد", "casual-shoes", "MEN", ["بني", "كحلي", "بيج"], SHOE_M, 115000, None, "loafers", "", None),
    ("s-m-sandal", "صندل جلد رجالي", "sandals-slippers", "MEN", ["بني", "أسود"], SHOE_M, 65000, None, "leather sandals", "", None),
    ("s-m-slipper", "شبشب طبي رجالي", "sandals-slippers", "MEN", ["أسود", "بني"], SHOE_M, 42000, None, "slippers", "", None),
    ("s-m-boots", "بوت شتوي رجالي", "boots", "MEN", ["بني", "أسود"], SHOE_M, 185000, None, "leather boots", "NEW", None),
    ("s-m-canvas", "كوتشي كانفاس", "casual-shoes", "MEN", ["أبيض", "أسود", "كحلي"], SHOE_M, 69000, None, "canvas sneakers", "", None),
    ("s-w-sneakers", "سنيكرز نسائي", "casual-shoes", "WOMEN", ["أبيض", "وردي", "بيج"], SHOE_W, 105000, None, "women's sneakers", "BEST_SELLER", None),
    ("s-w-heels", "حذاء كعب عالي", "heels-flats", "WOMEN", ["أسود", "بيج", "أحمر"], SHOE_W, 99000, None, "high heels", "", None),
    ("s-w-ballet", "باليرينا جلد", "heels-flats", "WOMEN", ["أسود", "بيج", "وردي"], SHOE_W, 65000, 52000, "ballet flats", "SALE", None),
    ("s-w-sandal", "صندل فلات نسائي", "sandals-slippers", "WOMEN", ["بيج", "أسود", "ذهبي"], SHOE_W, 55000, None, "women's sandals", "", None),
    ("s-w-boots", "بوت نسائي بكعب", "boots", "WOMEN", ["أسود", "بني"], SHOE_W, 175000, None, "women's boots", "", None),
    ("s-w-running", "حذاء رياضي نسائي", "sport-shoes", "WOMEN", ["وردي", "أبيض", "رمادي"], SHOE_W, 129000, None, "women's running shoes", "", None),
    ("s-w-slipper", "شبشب نسائي مريح", "sandals-slippers", "WOMEN", ["وردي", "أسود", "بيج"], SHOE_W, 32000, None, "flip flops", "", None),
    ("s-w-evening", "حذاء سواريه لامع", "heels-flats", "WOMEN", ["فضي", "ذهبي"], SHOE_W, 115000, None, "glitter heels", "", None),
    ("s-k-lights", "كوتشي أطفال بالأضواء", "sport-shoes", "BOYS", ["أزرق", "أحمر"], SHOE_K, 59000, None, "kids sneakers", "BEST_SELLER", None),
    ("s-k-sandal", "صندل بناتي", "sandals-slippers", "GIRLS", ["وردي", "أبيض"], SHOE_K, 35000, None, "children's sandals", "", None),
    ("s-k-girl-sneakers", "كوتشي بناتي", "casual-shoes", "GIRLS", ["وردي", "بنفسجي", "أبيض"], SHOE_K, 55000, None, "girls shoes", "", None),
    ("s-k-school", "حذاء مدرسة أطفال", "formal-shoes", "BOYS", ["أسود"], ("SHOE", ["28", "29", "30", "31", "32", "33", "34", "35", "36"]), 52000, None, "school shoes", "", None),
    # bags and accessories
    ("b-leather-hand", "شنطة يد جلد كبيرة", "handbags", "WOMEN", ["أسود", "بني", "بيج"], None, 135000, None, "leather handbag", "BEST_SELLER", None),
    ("b-crossbody", "شنطة كروس صغيرة", "handbags", "WOMEN", ["أسود", "أحمر", "بيج", "وردي"], None, 65000, 52000, "crossbody bag", "SALE", None),
    ("b-tote", "شنطة توت قماش", "handbags", "WOMEN", ["بيج", "أسود"], None, 45000, None, "tote bag", "", None),
    ("b-clutch", "كلاتش سهرة", "handbags", "WOMEN", ["ذهبي", "فضي", "أسود"], None, 59000, None, "clutch bag", "", None),
    ("b-shoulder", "شنطة كتف جلد", "handbags", "WOMEN", ["بني", "أسود", "زيتي"], None, 115000, None, "leather handbag brown|purse|handbag", "NEW", None),
    ("b-mini-backpack", "شنطة ظهر نسائي صغيرة", "backpacks", "WOMEN", ["أسود", "بيج", "وردي"], None, 69000, None, "mini backpack", "", None),
    ("b-laptop", "شنطة ظهر لابتوب", "backpacks", "MEN", ["أسود", "رمادي", "كحلي"], None, 89000, None, "laptop backpack", "", None),
    ("b-messenger", "شنطة كروس رجالي", "handbags", "MEN", ["بني", "أسود"], None, 59000, None, "satchel|leather satchel|briefcase", "", None),
    ("b-wallet-m", "محفظة جلد رجالي", "wallets", "MEN", ["أسود", "بني"], None, 38000, None, "leather wallet", "BEST_SELLER", None),
    ("b-cardholder", "حافظة كروت جلد", "wallets", "MEN", ["أسود", "بني", "كحلي"], None, 18000, None, "card holder", "", None),
    ("b-wallet-w", "محفظة نسائي طويلة", "wallets", "WOMEN", ["وردي", "أسود", "أحمر", "بيج"], None, 42000, None, "purse wallet|wallet|leather purse", "", None),
    ("b-suitcase", "شنطة سفر هارد", "travel-bags", "ALL", ["أسود", "كحلي", "رمادي", "أحمر"], None, 165000, None, "suitcase", "", ("الحجم", [("٢٠ بوصة (كابينة)", 0), ("٢٤ بوصة", 40000), ("٢٨ بوصة", 80000)])),
    ("b-duffel", "شنطة رياضة", "travel-bags", "MEN", ["أسود", "كحلي"], None, 62000, None, "duffel bag", "", None),
    ("b-school-boy", "شنطة مدرسة ولادي", "backpacks", "BOYS", ["أزرق", "أحمر"], None, 55000, None, "school backpack", "", None),
    ("b-school-girl", "شنطة مدرسة بناتي", "backpacks", "GIRLS", ["وردي", "بنفسجي"], None, 55000, None, "children's backpack", "", None),
    ("a-belt-m", "حزام جلد رجالي", "belts", "MEN", ["أسود", "بني"], ("APPAREL", ["M", "L", "XL"]), 35000, None, "leather belt", "", None),
    ("a-belt-w", "حزام نسائي رفيع", "belts", "WOMEN", ["أسود", "بيج", "بني"], None, 25000, None, "women's belt", "", None),
    ("a-scarf", "كوفية صوف شتوي", "scarves-hijabs", "MEN", ["رمادي", "كحلي", "بني"], None, 28000, None, "wool scarf", "", None),
    ("a-cap", "كاب قطن", "hats-caps", "ALL", ["أسود", "أبيض", "كحلي", "بيج"], None, 18000, None, "baseball cap", "", None),
]

SPECS = {
    "shirts-tops": [("الخامة", "قطن ١٠٠٪"), ("القصة", "ريجولار"), ("العناية", "غسيل ٣٠ درجة، كي متوسط")],
    "pants-jeans": [("الخامة", "قطن مع ٢٪ ليكرا"), ("القصة", "حسب الموديل"), ("العناية", "غسيل على الوش التاني، ٣٠ درجة")],
    "dresses": [("الخامة", "حسب الموديل"), ("الطول", "حسب الموديل"), ("العناية", "غسيل يدوي أو دراي كلين")],
    "bridal-evening": [("الخامة", "تول مطرز بالترتر مع بطانة"), ("العناية", "دراي كلين فقط")],
    "skirts": [("الخامة", "حسب الموديل"), ("الوسط", "أستك مريح")],
    "abayas-modest": [("الخامة", "كريب ثقيل مش شفاف"), ("الطول", "١٤٠ - ١٥٥ سم حسب المقاس"), ("العناية", "غسيل ٣٠ درجة")],
    "scarves-hijabs": [("الخامة", "حسب الموديل"), ("المقاس", "١٨٠ × ٧٠ سم")],
    "jackets-coats": [("الخامة", "حسب الموديل"), ("البطانة", "مبطن"), ("العناية", "دراي كلين")],
    "suits-blazers": [("الخامة", "خليط صوف وبوليستر"), ("القصة", "سليم فيت"), ("العناية", "دراي كلين")],
    "knitwear-hoodies": [("الخامة", "قطن مع بوليستر، من جوه فرو خفيف"), ("العناية", "غسيل ٣٠ درجة على الوش التاني")],
    "activewear": [("الخامة", "بوليستر دراي فيت بيسحب العرق"), ("العناية", "غسيل بارد")],
    "sleepwear": [("الخامة", "قطن ناعم"), ("العناية", "غسيل ٤٠ درجة")],
    "swimwear": [("الخامة", "ليكرا سريعة النشفان، مقاومة للكلور")],
    "underwear-socks": [("الخامة", "قطن ٩٥٪ مع ليكرا")],
    "traditional-wear": [("الخامة", "قطن مصري"), ("الطول", "للكعب")],
    "outfit-sets": [("الخامة", "قطن ١٠٠٪ مناسب لبشرة الأطفال")],
    "baby-clothing": [("الخامة", "قطن عضوي ناعم"), ("الفتح", "كباسين من تحت لتغيير البامبرز")],
    "casual-shoes": [("الخامة الخارجية", "جلد"), ("النعل", "كاوتش مرن"), ("بلد الصنع", "مصر")],
    "sport-shoes": [("الخامة الخارجية", "شبك خفيف بيهوّي"), ("النعل", "فوم مريح ضد الانزلاق")],
    "formal-shoes": [("الخامة الخارجية", "جلد طبيعي"), ("النعل", "جلد مع كاوتش")],
    "sandals-slippers": [("الخامة", "جلد أو بلاستيك طبي حسب الموديل"), ("النعل", "طبي مريح")],
    "boots": [("الخامة الخارجية", "جلد"), ("البطانة", "فرو"), ("النعل", "كاوتش ضد الانزلاق")],
    "heels-flats": [("الخامة الخارجية", "جلد صناعي"), ("ارتفاع الكعب", "حسب الموديل")],
    "handbags": [("الخامة", "جلد صناعي عالي الجودة"), ("الإغلاق", "سوستة"), ("الجيوب", "جيب داخلي بسوستة")],
    "backpacks": [("الخامة", "قماش مقاوم للمية"), ("الجيوب", "جيب لابتوب ١٥.٦ بوصة وجيبين جانب")],
    "wallets": [("الخامة", "جلد طبيعي"), ("السعة", "٨ كروت وجيب فلوس")],
    "travel-bags": [("الخامة", "بولي كربونات / قماش حسب الموديل"), ("العجل", "٤ عجلات ٣٦٠ درجة")],
    "belts": [("الخامة", "جلد طبيعي"), ("التوكة", "معدن")],
    "hats-caps": [("الخامة", "قطن"), ("المقاس", "قابل للتعديل")],
}

SIZE_GUIDE = {
    "MEN": "S: صدر 96 سم، وسط 30 · M: صدر 102، وسط 32 · L: صدر 108، وسط 34 · XL: صدر 114، وسط 36 · XXL: صدر 120، وسط 38. وزن 70-80 كيلو غالباً M، 80-90 L، 90-100 XL.",
    "WOMEN": "S: مقاس 36-38 · M: 40 · L: 42 · XL: 44 · XXL: 46. لو بين مقاسين خدي الأكبر.",
    "KIDS": "المقاس بالسن. لو الطفل أطول من سنه خد المقاس اللي بعده.",
    "SHOE": "المقاس أوروبي: 40 = 25.5 سم طول القدم · 41 = 26 · 42 = 27 · 43 = 27.5 · 44 = 28.5 · 45 = 29. للحريمي 36 = 23 سم · 37 = 23.5 · 38 = 24.5 · 39 = 25 · 40 = 25.5 · 41 = 26.",
}

FASHION_PROFILE = {
    "storeName": "نايل ستايل",
    "shortDescription": "ملابس رجالي وحريمي وأطفال، أحذية وشنط · توصيل لكل محافظات مصر والدفع عند الاستلام",
    "about": "نايل ستايل براند مصري للملابس والأحذية والشنط. خامات قطن مصري، مقاسات من S لحد 3XL، وتشكيلة أطفال من حديثي الولادة لحد ١٢ سنة. كل منتج له جدول مقاسات، ولو المقاس مش مظبوط بنبدله ببلاش.",
    "announcement": "❄️ تشكيلة الشتاء وصلت · كود NILE10 خصم ١٠٪ على الطلبات من ١٠٠٠ جنيه · شحن مجاني من ١٥٠٠ جنيه",
    "shippingPolicy": "بنشحن لكل محافظات مصر. القاهرة والجيزة ٥٠ جنيه وتوصل في يوم أو يومين. الإسكندرية والدلتا ٦٥ جنيه في ٢ - ٣ أيام. القناة وسيناء والبحر الأحمر ٨٥ جنيه في ٣ - ٥ أيام. الصعيد ٨٥ جنيه في ٣ - ٥ أيام. الشحن مجاني من ١٥٠٠ جنيه. تقدر تعاين الأوردر مع المندوب قبل ما تدفع.",
    "returnPolicy": "الاسترجاع والاستبدال خلال ١٤ يوم من الاستلام، بشرط المنتج يكون بحالته وبالتيكت. تبديل المقاس أول مرة ببلاش. الملابس الداخلية والمايوهات مش بتترجع لأسباب صحية. الفلوس بترجع خلال ٧ أيام من استلامنا المرتجع.",
    "supportPhone": "01001234567", "whatsapp": "01001234567", "addressText": "مول العرب، الدور الأول، ٦ أكتوبر، الجيزة",
    "mapsUrl": "https://maps.google.com/?q=Mall+of+Arabia+6th+October",
    "returnWindowDays": 14, "minOrderMinor": 0, "vatIncluded": True, "vatPercent": 14,
}

FASHION_ZONES = [
    ("القاهرة والجيزة", ["CAI", "GIZ", "QLY"], 5000, 1, 2),
    ("الإسكندرية والدلتا", ["ALX", "BHR", "DKH", "GHR", "KFS", "MNF", "SHR", "DMT"], 6500, 2, 3),
    ("القناة وسيناء والبحر الأحمر", ["ISM", "PSD", "SUZ", "NSN", "SSN", "RSS"], 8500, 3, 5),
    ("الصعيد", ["FYM", "BNS", "MNY", "AST", "SHG", "QNA", "LXR", "ASN", "WAD", "MTR"], 8500, 3, 5),
]

# ---------------------------------------------------------------- electronics
# Electronics item: (key, name, category slug, base price, colours ([] = none), [(option, [(value, price added)])], specs [(k, v)], badge, compare-at base or None, tags, brand)
# The brands are made up for the demo (no real brand's prices or specs are invented); compatibility with real phones is stated the way a shop writes it.
STORAGE = lambda *steps: ("السعة", [(v, add) for v, add in steps])
COND = lambda off: ("الحالة", [("جديد", 0), ("أوبن بوكس", -off)])
RAMSSD = lambda ram, ssd: [("الرامات", [(v, a) for v, a in ram]), ("التخزين", [(v, a) for v, a in ssd])]
TV_SIZE = lambda *steps: ("المقاس", [(v, add) for v, add in steps])
TV_MOUNT = ("التركيب", [("بدون تركيب", 0), ("تركيب على الحيطة مع الحامل", 45000)])
LEN = ("الطول", [("1 متر", 0), ("2 متر", 4000)])
W1Y = ("الضمان", "سنة من الوكيل")
W6M = ("الضمان", "٦ شهور")
WOB = ("الضمان", "٦ شهور (أوبن بوكس: علبة مفتوحة، الجهاز سليم ١٠٠٪)")


def phone_specs(screen, cpu, ram, cam, battery, charging, box="الموبايل، كابل USB-C، دبوس الشريحة"):
    return [("الشاشة", screen), ("المعالج", cpu), ("الرامات", ram), ("الكاميرا", cam), ("البطارية", battery), ("الشحن", charging), ("الشريحة", "شريحتين نانو"), ("في العلبة", box), W1Y]


ELECTRONICS = [
    # phones
    ("ph-x12", "موبايل نوفا X12", "phones", 1499900, ["أسود", "أزرق", "فضي"], [STORAGE(("128 جيجا", 0), ("256 جيجا", 200000)), COND(150000)],
     phone_specs("6.5 بوصة AMOLED 120 هرتز", "ثماني النواة 2.8 جيجاهرتز", "8 جيجا", "خلفية 50 ميجا + 12 واسعة، أمامية 16", "5000 مللي أمبير", "33 وات سريع (الشاحن مش في العلبة)"), "BEST_SELLER", None, ["موبايل", "5G"], "نوفا"),
    ("ph-x12-pro", "موبايل نوفا X12 برو", "phones", 2199900, ["أسود", "ذهبي", "فضي"], [STORAGE(("256 جيجا", 0), ("512 جيجا", 300000)), COND(200000)],
     phone_specs("6.7 بوصة AMOLED 120 هرتز", "ثماني النواة 3.2 جيجاهرتز", "12 جيجا", "خلفية 200 ميجا + 12 واسعة + 10 زووم 3x، أمامية 32", "5100 مللي أمبير", "67 وات سريع + شحن لاسلكي 15 وات"), "NEW", None, ["موبايل", "5G", "كاميرا"], "نوفا"),
    ("ph-a5", "موبايل نوفا A5", "phones", 549900, ["أسود", "أزرق", "أخضر"], [STORAGE(("64 جيجا", 0), ("128 جيجا", 60000))],
     phone_specs("6.6 بوصة LCD 90 هرتز", "ثماني النواة 2.0 جيجاهرتز", "4 جيجا", "خلفية 50 ميجا، أمامية 8", "5000 مللي أمبير", "18 وات", "الموبايل، شاحن 18 وات، كابل USB-C، جراب سيليكون"), "BEST_SELLER", 599900, ["موبايل", "اقتصادي"], "نوفا"),
    ("ph-a7", "موبايل نوفا A7", "phones", 749900, ["أسود", "بنفسجي"], [STORAGE(("128 جيجا", 0), ("256 جيجا", 80000))],
     phone_specs("6.7 بوصة AMOLED 90 هرتز", "ثماني النواة 2.4 جيجاهرتز", "6 جيجا", "خلفية 64 ميجا + 8 واسعة، أمامية 16", "5000 مللي أمبير", "25 وات", "الموبايل، شاحن 25 وات، كابل USB-C"), "", None, ["موبايل"], "نوفا"),
    ("ph-lite3", "موبايل نوفا لايت 3", "phones", 399900, ["أسود", "أزرق", "أحمر"], [STORAGE(("64 جيجا", 0),)],
     phone_specs("6.5 بوصة LCD", "رباعي النواة", "3 جيجا", "خلفية 13 ميجا، أمامية 5", "5000 مللي أمبير", "10 وات", "الموبايل، شاحن، كابل micro USB"), "", None, ["موبايل", "اقتصادي", "للكبار في السن"], "نوفا"),
    ("ph-mini8", "موبايل نوفا ميني 8", "phones", 1099900, ["أبيض", "وردي", "أسود"], [STORAGE(("128 جيجا", 0), ("256 جيجا", 150000))],
     phone_specs("5.9 بوصة AMOLED صغيرة في الإيد", "ثماني النواة 2.8 جيجاهرتز", "8 جيجا", "خلفية 50 ميجا، أمامية 12", "4300 مللي أمبير", "30 وات"), "", None, ["موبايل", "صغير"], "نوفا"),
    ("ph-max15", "موبايل نوفا ماكس 15", "phones", 1849900, ["رمادي", "أزرق"], [STORAGE(("256 جيجا", 0), ("512 جيجا", 250000))],
     phone_specs("6.9 بوصة AMOLED 144 هرتز", "ثماني النواة 3.0 جيجاهرتز", "12 جيجا", "خلفية 108 ميجا + 12 واسعة، أمامية 20", "6000 مللي أمبير", "45 وات"), "", 1999900, ["موبايل", "بطارية كبيرة", "ألعاب"], "نوفا"),
    ("ph-fold", "موبايل نوفا فولد Z", "phones", 5499900, ["أسود"], [STORAGE(("512 جيجا", 0),)],
     phone_specs("داخلية 7.8 بوصة + خارجية 6.3 بوصة AMOLED", "ثماني النواة 3.2 جيجاهرتز", "16 جيجا", "خلفية 50 ميجا ×3، أمامية 10", "4800 مللي أمبير", "45 وات + لاسلكي"), "NEW", None, ["موبايل", "قابل للطي"], "نوفا"),
    ("ph-x10", "موبايل نوفا X10", "phones", 1149900, ["أسود", "فضي"], [STORAGE(("128 جيجا", 0), ("256 جيجا", 150000))],
     phone_specs("6.4 بوصة AMOLED 90 هرتز", "ثماني النواة 2.4 جيجاهرتز", "8 جيجا", "خلفية 48 ميجا، أمامية 16", "4500 مللي أمبير", "33 وات"), "SALE", 1349900, ["موبايل"], "نوفا"),
    # tablets
    ("tb-tab10", "تابلت نوفا تاب 10", "tablets", 899900, ["رمادي", "فضي"], [STORAGE(("64 جيجا", 0), ("128 جيجا", 100000)), ("الاتصال", [("واي فاي بس", 0), ("واي فاي + شريحة", 150000)])],
     [("الشاشة", "10.4 بوصة 2K"), ("المعالج", "ثماني النواة"), ("الرامات", "4 جيجا"), ("البطارية", "7040 مللي أمبير"), ("في العلبة", "التابلت، شاحن، كابل USB-C"), W1Y], "BEST_SELLER", None, ["تابلت", "مذاكرة"], "نوفا"),
    ("tb-tab12", "تابلت نوفا تاب 12 برو", "tablets", 1899900, ["رمادي"], [STORAGE(("256 جيجا", 0), ("512 جيجا", 250000))],
     [("الشاشة", "12.4 بوصة AMOLED 120 هرتز"), ("الرامات", "8 جيجا"), ("القلم", "القلم الذكي في العلبة"), ("البطارية", "10090 مللي أمبير"), W1Y], "NEW", None, ["تابلت", "رسم"], "نوفا"),
    ("tb-kids", "تابلت أطفال نوفا كيدز 8", "tablets", 349900, ["أزرق", "وردي"], [STORAGE(("32 جيجا", 0),)],
     [("الشاشة", "8 بوصة HD"), ("مناسب لسن", "3 - 10 سنين"), ("الجراب", "جراب مطاط ضد الوقوع في العلبة"), ("التحكم", "رقابة أبوية"), W1Y], "", 399900, ["تابلت", "أطفال", "هدية"], "نوفا"),
    ("tb-lite", "تابلت نوفا تاب لايت", "tablets", 549900, ["رمادي"], [STORAGE(("64 جيجا", 0),)],
     [("الشاشة", "8.7 بوصة"), ("الرامات", "3 جيجا"), ("البطارية", "5100 مللي أمبير"), W1Y], "", None, ["تابلت", "اقتصادي"], "نوفا"),
    # laptops
    ("lp-book14", "لاب توب زينترا بوك 14", "laptops", 2499900, ["فضي", "رمادي"], RAMSSD([("8 جيجا", 0), ("16 جيجا", 300000)], [("512 جيجا SSD", 0), ("1 تيرا SSD", 250000)]),
     [("الشاشة", "14 بوصة FHD IPS"), ("المعالج", "كور i5 الجيل 13"), ("كارت الشاشة", "مدمج"), ("الوزن", "1.4 كيلو"), ("البطارية", "لحد 10 ساعات"), ("النظام", "ويندوز 11 أصلي"), ("الكيبورد", "عربي / إنجليزي مضيء"), W1Y], "BEST_SELLER", None, ["لاب توب", "شغل", "مذاكرة"], "زينترا"),
    ("lp-book15", "لاب توب زينترا بوك 15 برو", "laptops", 3499900, ["رمادي"], RAMSSD([("16 جيجا", 0), ("32 جيجا", 400000)], [("512 جيجا SSD", 0), ("1 تيرا SSD", 250000)]),
     [("الشاشة", "15.6 بوصة FHD 144 هرتز"), ("المعالج", "كور i7 الجيل 13"), ("كارت الشاشة", "RTX 3050 6 جيجا"), ("الوزن", "1.9 كيلو"), ("النظام", "ويندوز 11 أصلي"), ("الكيبورد", "عربي / إنجليزي مضيء"), W1Y], "", None, ["لاب توب", "برمجة", "تصميم"], "زينترا"),
    ("lp-gamer16", "لاب توب جيمنج زينترا جيمر 16", "laptops", 5299900, ["أسود"], RAMSSD([("16 جيجا", 0), ("32 جيجا", 400000)], [("1 تيرا SSD", 0), ("2 تيرا SSD", 400000)]),
     [("الشاشة", "16 بوصة QHD 165 هرتز"), ("المعالج", "كور i7 الجيل 14"), ("كارت الشاشة", "RTX 4060 8 جيجا"), ("التبريد", "مروحتين + 5 أنابيب حرارية"), ("الوزن", "2.4 كيلو"), W1Y], "NEW", None, ["لاب توب", "ألعاب", "جيمنج"], "زينترا"),
    ("lp-air13", "لاب توب زينترا إير 13", "laptops", 2899900, ["فضي", "ذهبي"], RAMSSD([("16 جيجا", 0),], [("512 جيجا SSD", 0), ("1 تيرا SSD", 250000)]),
     [("الشاشة", "13.3 بوصة 2.5K"), ("المعالج", "كور i5 الجيل 13 موفر"), ("الوزن", "1.1 كيلو خفيف جداً"), ("البطارية", "لحد 14 ساعة"), W1Y], "", 3199900, ["لاب توب", "خفيف", "سفر"], "زينترا"),
    ("lp-student", "لاب توب زينترا ستيودنت 15", "laptops", 1599900, ["رمادي"], RAMSSD([("8 جيجا", 0),], [("256 جيجا SSD", 0), ("512 جيجا SSD", 120000)]),
     [("الشاشة", "15.6 بوصة FHD"), ("المعالج", "كور i3 الجيل 12"), ("كارت الشاشة", "مدمج"), ("النظام", "ويندوز 11 أصلي"), W1Y], "BEST_SELLER", None, ["لاب توب", "طلبة", "اقتصادي"], "زينترا"),
    ("lp-creator", "لاب توب زينترا كرييتور 16", "laptops", 6499900, ["فضي"], RAMSSD([("32 جيجا", 0), ("64 جيجا", 600000)], [("1 تيرا SSD", 0), ("2 تيرا SSD", 400000)]),
     [("الشاشة", "16 بوصة OLED 4K"), ("المعالج", "كور i9 الجيل 14"), ("كارت الشاشة", "RTX 4070 8 جيجا"), ("الوزن", "2.1 كيلو"), W1Y], "", None, ["لاب توب", "مونتاج", "تصميم"], "زينترا"),
    ("lp-book14-ob", "لاب توب زينترا بوك 14 أوبن بوكس", "laptops", 2099900, ["فضي"], RAMSSD([("8 جيجا", 0),], [("512 جيجا SSD", 0),]),
     [("الحالة", "أوبن بوكس: العلبة مفتوحة، الجهاز جديد مستخدمش"), ("الشاشة", "14 بوصة FHD IPS"), ("المعالج", "كور i5 الجيل 13"), WOB], "SALE", 2499900, ["لاب توب", "أوبن بوكس"], "زينترا"),
    # tvs
    ("tv-4k", "تلفزيون فيستا 4K سمارت", "tvs", 1499900, [], [TV_SIZE(("43 بوصة", 0), ("50 بوصة", 300000), ("55 بوصة", 550000), ("65 بوصة", 1250000)), TV_MOUNT],
     [("الدقة", "4K UHD HDR10"), ("النظام", "أندرويد تي في، نتفليكس ويوتيوب"), ("الريسيفر", "داخلي"), ("المداخل", "3 HDMI و 2 USB"), ("الضمان", "سنتين"), ("التوصيل", "التلفزيونات من 50 بوصة بتوصل بعربية خاصة في القاهرة والجيزة")], "BEST_SELLER", None, ["تلفزيون", "شاشة", "سمارت"], "فيستا"),
    ("tv-qled", "تلفزيون فيستا QLED", "tvs", 2599900, [], [TV_SIZE(("55 بوصة", 0), ("65 بوصة", 900000)), TV_MOUNT],
     [("الدقة", "4K QLED 120 هرتز"), ("النظام", "جوجل تي في"), ("الصوت", "دولبي أتموس 40 وات"), ("الضمان", "سنتين")], "NEW", None, ["تلفزيون", "شاشة"], "فيستا"),
    ("tv-hd", "تلفزيون فيستا HD", "tvs", 549900, [], [TV_SIZE(("32 بوصة", 0), ("43 بوصة FHD", 250000)), TV_MOUNT],
     [("الدقة", "HD / FHD"), ("سمارت", "لأ (شاشة عادية بريسيفر)"), ("الضمان", "سنتين")], "", 599900, ["تلفزيون", "اقتصادي"], "فيستا"),
    # audio
    ("au-buds-pro", "سماعة سونيك بادز برو", "phone-earphones", 249900, ["أبيض", "أسود"], [],
     [("النوع", "إيربودز لاسلكية"), ("عزل الضوضاء", "ANC نشط"), ("البطارية", "8 ساعات + 24 بالعلبة"), ("المقاومة", "IPX4 للعرق"), ("التوافق", "أي موبايل بلوتوث (أندرويد وآيفون)"), W1Y], "BEST_SELLER", None, ["سماعة", "بلوتوث", "إيربودز"], "سونيك"),
    ("au-buds-lite", "سماعة سونيك بادز لايت", "phone-earphones", 79900, ["أبيض", "أسود", "أزرق"], [],
     [("النوع", "إيربودز لاسلكية"), ("البطارية", "5 ساعات + 20 بالعلبة"), ("التوافق", "أي موبايل بلوتوث"), W6M], "", 99900, ["سماعة", "بلوتوث", "اقتصادي"], "سونيك"),
    ("au-max", "سماعة رأس سونيك ماكس", "headphones", 399900, ["أسود", "فضي", "أزرق"], [],
     [("النوع", "سماعة رأس لاسلكية فوق الأذن"), ("عزل الضوضاء", "ANC نشط"), ("البطارية", "40 ساعة"), ("السلك", "سلك 3.5 مم في العلبة"), W1Y], "NEW", None, ["سماعة", "بلوتوث"], "سونيك"),
    ("au-kids", "سماعة رأس أطفال سونيك", "headphones", 64900, ["أزرق", "وردي"], [],
     [("الصوت", "محدود 85 ديسيبل لحماية الودن"), ("النوع", "سلك 3.5 مم"), ("مناسب لسن", "3 - 12 سنة"), W6M], "", None, ["سماعة", "أطفال"], "سونيك"),
    ("au-neck", "سماعة رقبة رياضية سونيك", "phone-earphones", 59900, ["أسود", "أحمر"], [],
     [("النوع", "نيك باند بلوتوث"), ("البطارية", "20 ساعة"), ("المقاومة", "IPX5"), W6M], "", None, ["سماعة", "رياضة"], "سونيك"),
    ("au-wired", "سماعة سلك سونيك USB-C", "phone-earphones", 29900, ["أبيض"], [],
     [("المدخل", "USB-C (مش 3.5 مم)"), ("التوافق", "الموبايلات اللي مدخلها USB-C، وآيفون 15 وأحدث"), ("الميكروفون", "فيه"), W6M], "", None, ["سماعة", "سلك"], "سونيك"),
    ("au-gaming", "سماعة جيمنج سونيك برو", "gaming-accessories", 189900, ["أسود"], [],
     [("النوع", "سلك USB + 3.5 مم"), ("الصوت", "محيطي 7.1 افتراضي"), ("الميكروفون", "قابل للفصل"), ("التوافق", "كمبيوتر، بلايستيشن، إكس بوكس، موبايل"), W1Y], "", None, ["سماعة", "جيمنج"], "سونيك"),
    ("sp-boom", "سبيكر سونيك بوم", "speakers-audio", 149900, ["أسود", "أزرق", "أحمر"], [],
     [("النوع", "سبيكر بلوتوث محمول"), ("القوة", "20 وات"), ("البطارية", "12 ساعة"), ("المقاومة", "IPX7 ضد المية"), W1Y], "BEST_SELLER", 179900, ["سبيكر", "بلوتوث"], "سونيك"),
    ("sp-bar", "ساوند بار سونيك 2.1", "speakers-audio", 449900, ["أسود"], [],
     [("القوة", "240 وات مع سب ووفر لاسلكي"), ("التوصيل", "HDMI ARC وبلوتوث وأوبتيكال"), W1Y], "", None, ["ساوند بار", "تلفزيون"], "سونيك"),
    ("sp-mini", "سبيكر سونيك ميني", "speakers-audio", 49900, ["أسود", "أبيض", "وردي"], [],
     [("القوة", "5 وات"), ("البطارية", "6 ساعات"), W6M], "", None, ["سبيكر", "هدية"], "سونيك"),
    # smart watches
    ("sw-pulse5", "ساعة ذكية بلس 5", "smart-watches", 399900, ["أسود", "أبيض", "وردي"], [("المقاس", [("41 مم", 0), ("45 مم", 50000)])],
     [("الشاشة", "AMOLED دايماً شغالة"), ("المميزات", "نبض، أكسجين، نوم، 100 رياضة، مكالمات بلوتوث"), ("البطارية", "لحد 7 أيام"), ("التوافق", "أندرويد وآيفون"), ("المقاومة", "5 ATM للسباحة"), W1Y], "BEST_SELLER", None, ["ساعة ذكية", "رياضة"], "بلس"),
    ("sw-pro", "ساعة ذكية بلس برو", "smart-watches", 749900, ["أسود", "فضي"], [("المقاس", [("45 مم", 0),])],
     [("الشاشة", "AMOLED ياقوت"), ("المميزات", "GPS، ضغط، رسم قلب، مكالمات"), ("البطارية", "لحد 14 يوم"), ("التوافق", "أندرويد وآيفون"), W1Y], "NEW", None, ["ساعة ذكية"], "بلس"),
    ("sw-band", "سوار ذكي بلس فيت", "smart-watches", 99900, ["أسود", "أزرق"], [],
     [("المميزات", "خطوات، نبض، نوم، إشعارات"), ("البطارية", "14 يوم"), ("التوافق", "أندرويد وآيفون"), W6M], "", 129900, ["سوار", "رياضة"], "بلس"),
    ("sw-kids", "ساعة أطفال بلس كيدز بشريحة", "smart-watches", 179900, ["أزرق", "وردي"], [],
     [("المميزات", "مكالمات بشريحة، تتبع المكان GPS، زرار طوارئ"), ("الشريحة", "نانو (مش في العلبة)"), ("مناسب لسن", "5 - 12 سنة"), W6M], "", None, ["ساعة", "أطفال"], "بلس"),
    # chargers and cables
    ("ch-20w", "شاحن فولتا 20 وات USB-C", "chargers-cables", 39900, ["أبيض"], [],
     [("المخرج", "USB-C واحد، 20 وات PD"), ("التوافق", "آيفون 8 لحد آيفون 16 (مع كابل USB-C لـ Lightning أو USB-C)، وأي موبايل USB-C"), ("الكابل", "مش معاه (بيتباع لوحده)"), W1Y], "BEST_SELLER", None, ["شاحن", "آيفون", "سريع"], "فولتا"),
    ("ch-33w", "شاحن فولتا 33 وات", "chargers-cables", 54900, ["أبيض", "أسود"], [],
     [("المخرج", "USB-C و USB-A، لحد 33 وات"), ("التوافق", "موبايلات أندرويد بالشحن السريع، وآيفون"), ("الكابل", "كابل USB-C لـ USB-C متر معاه"), W1Y], "", None, ["شاحن", "سريع"], "فولتا"),
    ("ch-65w", "شاحن فولتا 65 وات GaN", "chargers-cables", 119900, ["أبيض", "أسود"], [],
     [("المخرج", "2 USB-C + USB-A، لحد 65 وات"), ("التوافق", "لاب توب USB-C، موبايل، تابلت"), ("الحجم", "صغير بتقنية GaN"), W1Y], "NEW", None, ["شاحن", "لاب توب"], "فولتا"),
    ("ch-car", "شاحن عربية فولتا 38 وات", "chargers-cables", 34900, ["أسود"], [],
     [("المخرج", "USB-C 20 وات + USB-A 18 وات"), ("التوافق", "ولاعة العربية 12 / 24 فولت"), W6M], "", None, ["شاحن", "عربية"], "فولتا"),
    ("ch-wireless", "شاحن لاسلكي فولتا 15 وات", "chargers-cables", 49900, ["أسود", "أبيض"], [],
     [("القوة", "15 وات"), ("التوافق", "أي موبايل بيدعم الشحن اللاسلكي Qi (آيفون 8 وأحدث، نوفا X12 برو)"), ("المحول", "مش معاه، محتاج شاحن 20 وات أو أكتر"), W6M], "", 59900, ["شاحن", "لاسلكي"], "فولتا"),
    ("cb-cc", "كابل فولتا USB-C لـ USB-C", "chargers-cables", 14900, ["أبيض", "أسود"], [LEN],
     [("القوة", "لحد 60 وات"), ("التوافق", "موبايلات وتابلت ولاب توب USB-C، وآيفون 15 وأحدث"), W6M], "BEST_SELLER", None, ["كابل", "USB-C"], "فولتا"),
    ("cb-cl", "كابل فولتا USB-C لـ Lightning", "chargers-cables", 19900, ["أبيض"], [LEN],
     [("التوافق", "آيفون 5 لحد آيفون 14 (مش آيفون 15)، آيباد بمدخل Lightning"), ("الشحن السريع", "بيدعمه مع شاحن 20 وات"), ("الشهادة", "MFi معتمد"), W6M], "", None, ["كابل", "آيفون"], "فولتا"),
    ("cb-ac", "كابل فولتا USB-A لـ USB-C", "chargers-cables", 9900, ["أسود", "أبيض"], [LEN],
     [("القوة", "لحد 3 أمبير"), ("التوافق", "شواحن USB-A القديمة مع موبايلات USB-C"), W6M], "", None, ["كابل"], "فولتا"),
    ("cb-micro", "كابل فولتا micro USB", "chargers-cables", 7900, ["أسود"], [("الطول", [("1 متر", 0),])],
     [("التوافق", "الموبايلات والأجهزة القديمة بمدخل micro USB (نوفا لايت 3)"), W6M], "", None, ["كابل"], "فولتا"),
    ("cb-3in1", "كابل فولتا 3 في 1", "chargers-cables", 17900, ["أسود"], [],
     [("الأطراف", "USB-C و Lightning و micro USB"), ("الطول", "1.2 متر"), W6M], "", 22900, ["كابل"], "فولتا"),
    ("ch-bundle", "طقم شاحن 20 وات + كابل USB-C لـ Lightning", "chargers-cables", 54900, ["أبيض"], [],
     [("في العلبة", "شاحن 20 وات USB-C + كابل USB-C لـ Lightning متر"), ("التوافق", "آيفون 8 لحد آيفون 14"), W1Y], "SALE", 59800, ["شاحن", "آيفون", "عرض"], "فولتا"),
    # power banks
    ("pb-10k", "باور بانك فولتا 10000", "power-banks", 59900, ["أسود", "أبيض"], [],
     [("السعة", "10000 مللي أمبير (بيشحن الموبايل حوالي مرتين)"), ("المخرج", "USB-C 20 وات + USB-A"), ("مسموح في الطيارة", "أيوه"), W1Y], "BEST_SELLER", None, ["باور بانك", "سفر"], "فولتا"),
    ("pb-20k", "باور بانك فولتا 20000 PD", "power-banks", 89900, ["أسود"], [],
     [("السعة", "20000 مللي أمبير (حوالي 4 مرات)"), ("المخرج", "USB-C 22.5 وات + 2 USB-A"), ("مسموح في الطيارة", "أيوه"), W1Y], "", 99900, ["باور بانك"], "فولتا"),
    ("pb-30k", "باور بانك فولتا 30000 للاب توب", "power-banks", 189900, ["رمادي"], [],
     [("السعة", "30000 مللي أمبير"), ("المخرج", "USB-C 65 وات بيشحن لاب توب USB-C"), ("مسموح في الطيارة", "لأ، أكبر من الحد المسموح"), W1Y], "", None, ["باور بانك", "لاب توب"], "فولتا"),
    ("pb-mag", "باور بانك مغناطيسي فولتا 5000", "power-banks", 69900, ["أبيض", "وردي", "أزرق"], [],
     [("السعة", "5000 مللي أمبير"), ("التوافق", "بيلزق مغناطيس في آيفون 12 وأحدث (MagSafe)، وغيره بجراب مغناطيسي"), ("الشحن", "لاسلكي 7.5 وات"), W6M], "NEW", None, ["باور بانك", "آيفون", "مغناطيس"], "فولتا"),
    # cases and protectors
    ("cs-x12", "جراب نوفا X12 سيليكون", "phone-cases", 19900, ["أسود", "أزرق", "أحمر", "وردي"], [],
     [("التوافق", "نوفا X12 بس (مش X12 برو)"), ("الخامة", "سيليكون ناعم من جوه مايكروفايبر"), ("الحماية", "حواف مرفوعة للكاميرا والشاشة")], "BEST_SELLER", None, ["جراب", "نوفا"], "آرمر"),
    ("cs-x12pro", "جراب نوفا X12 برو ضد الصدمات", "phone-cases", 29900, ["أسود", "أزرق"], [],
     [("التوافق", "نوفا X12 برو بس"), ("الخامة", "TPU وبولي كربونات"), ("الحماية", "اختبار وقوع 2 متر")], "", None, ["جراب", "نوفا"], "آرمر"),
    ("cs-a5", "جراب نوفا A5 بحامل", "phone-cases", 14900, ["أسود", "أخضر"], [],
     [("التوافق", "نوفا A5"), ("مميزات", "حلقة حامل في الضهر")], "", None, ["جراب", "نوفا"], "آرمر"),
    ("cs-ip15", "جراب آيفون 15 شفاف ماج سيف", "phone-cases", 34900, ["أسود", "أزرق", "بنفسجي"], [("الموديل", [("آيفون 15", 0), ("آيفون 15 برو", 0), ("آيفون 15 برو ماكس", 3000)])],
     [("التوافق", "آيفون 15 بموديلاته (اختار الموديل)"), ("المغناطيس", "حلقة MagSafe"), ("اللون", "ضهر شفاف وحواف ملونة")], "", None, ["جراب", "آيفون"], "آرمر"),
    ("cs-ip14", "جراب آيفون 14 سيليكون", "phone-cases", 24900, ["أسود", "وردي", "أبيض"], [("الموديل", [("آيفون 14", 0), ("آيفون 14 برو", 0)])],
     [("التوافق", "آيفون 14 و 14 برو (اختار الموديل)"), ("الخامة", "سيليكون")], "", 29900, ["جراب", "آيفون"], "آرمر"),
    ("cs-flip", "جراب جلد قلاب نوفا X12", "phone-cases", 34900, ["بني", "أسود"], [],
     [("التوافق", "نوفا X12"), ("مميزات", "جيب كروت ويتقلب ستاند")], "", None, ["جراب", "جلد"], "آرمر"),
    ("gl-x12", "اسكرينة جلاس نوفا X12", "screen-protectors", 9900, [], [],
     [("التوافق", "نوفا X12 و X12 برو"), ("الصلابة", "9H"), ("التركيب", "إطار تركيب في العلبة")], "BEST_SELLER", None, ["اسكرينة", "نوفا"], "آرمر"),
    ("gl-ip15", "اسكرينة جلاس آيفون 15", "screen-protectors", 14900, [], [("الموديل", [("آيفون 15", 0), ("آيفون 15 برو", 0), ("آيفون 15 برو ماكس", 2000)])],
     [("التوافق", "آيفون 15 بموديلاته"), ("الصلابة", "9H")], "", None, ["اسكرينة", "آيفون"], "آرمر"),
    ("gl-privacy", "اسكرينة خصوصية نوفا X12", "screen-protectors", 17900, [], [],
     [("التوافق", "نوفا X12"), ("الخصوصية", "الشاشة مش باينة من الجنب")], "", None, ["اسكرينة"], "آرمر"),
    ("gl-tab10", "اسكرينة تابلت نوفا تاب 10", "screen-protectors", 19900, [], [],
     [("التوافق", "نوفا تاب 10"), ("النوع", "ورق مطفي للكتابة")], "", None, ["اسكرينة", "تابلت"], "آرمر"),
    # computer accessories
    ("ac-mouse", "ماوس لاسلكي زينترا", "computer-accessories", 29900, ["أسود", "أبيض", "وردي"], [],
     [("الاتصال", "دونجل USB + بلوتوث"), ("البطارية", "حجر AA بيعيش 12 شهر"), ("التوافق", "ويندوز وماك وتابلت"), W6M], "BEST_SELLER", None, ["ماوس"], "زينترا"),
    ("ac-gmouse", "ماوس جيمنج زينترا RGB", "gaming-accessories", 69900, ["أسود"], [],
     [("الحساسية", "لحد 16000 DPI"), ("الأزرار", "7 قابلة للبرمجة"), ("الإضاءة", "RGB"), W1Y], "", 79900, ["ماوس", "جيمنج"], "زينترا"),
    ("ac-kb", "كيبورد لاسلكي زينترا عربي", "computer-accessories", 49900, ["أسود", "أبيض"], [],
     [("الحروف", "عربي / إنجليزي محفورة"), ("الاتصال", "دونجل USB"), W6M], "", None, ["كيبورد"], "زينترا"),
    ("ac-mech", "كيبورد ميكانيكال زينترا", "gaming-accessories", 149900, ["أسود"], [("نوع السويتش", [("أحمر هادي", 0), ("أزرق بصوت", 0), ("بني", 0)])],
     [("الحروف", "عربي / إنجليزي مضيء RGB"), ("الحجم", "75٪"), ("الاتصال", "سلك USB-C وبلوتوث"), W1Y], "NEW", None, ["كيبورد", "جيمنج"], "زينترا"),
    ("ac-stand", "ستاند لاب توب ألومنيوم", "computer-accessories", 39900, ["فضي", "رمادي"], [],
     [("المقاسات", "لاب توب من 10 لـ 17 بوصة"), ("التعديل", "6 ارتفاعات"), ("الخامة", "ألومنيوم")], "", None, ["ستاند", "لاب توب"], "زينترا"),
    ("ac-hub", "هاب زينترا USB-C 7 في 1", "computer-accessories", 89900, ["رمادي"], [],
     [("المداخل", "HDMI 4K، 3 USB-A، SD، microSD، USB-C 100 وات"), ("التوافق", "لاب توب وتابلت بمدخل USB-C"), W1Y], "", None, ["هاب", "لاب توب"], "زينترا"),
    ("ac-cam", "ويب كام زينترا 1080p", "computer-accessories", 79900, ["أسود"], [],
     [("الدقة", "فل HD 1080p"), ("الميكروفون", "مدمج"), ("التوافق", "زووم وتيمز وجوجل ميت، ويندوز وماك"), W1Y], "", 99900, ["كاميرا", "أونلاين"], "زينترا"),
    ("ac-pad", "ماوس باد زينترا XL", "gaming-accessories", 24900, ["أسود"], [],
     [("المقاس", "90 × 40 سم"), ("الخامة", "قماش مع ضهر مطاط")], "", None, ["ماوس باد", "جيمنج"], "زينترا"),
    ("st-flash", "فلاشة زينترا USB 3.2", "storage-memory", 19900, ["أسود"], [STORAGE(("64 جيجا", 0), ("128 جيجا", 10000), ("256 جيجا", 30000))],
     [("السرعة", "لحد 150 ميجا/ثانية"), W1Y], "", None, ["فلاشة", "تخزين"], "زينترا"),
    ("st-ssd", "هارد SSD خارجي زينترا", "storage-memory", 299900, ["رمادي"], [STORAGE(("1 تيرا", 0), ("2 تيرا", 250000))],
     [("السرعة", "لحد 1050 ميجا/ثانية"), ("المدخل", "USB-C (كابل USB-C و USB-A معاه)"), ("التوافق", "ويندوز وماك وبلايستيشن 5"), ("الضمان", "3 سنين")], "", None, ["هارد", "تخزين"], "زينترا"),
    ("st-sd", "كارت ميموري microSD زينترا", "storage-memory", 24900, [], [STORAGE(("64 جيجا", 0), ("128 جيجا", 15000), ("256 جيجا", 40000))],
     [("الفئة", "A2 V30 للكاميرات والموبايلات"), ("المحول", "محول SD معاه"), W1Y], "", None, ["ميموري", "تخزين"], "زينترا"),
    ("nw-router", "راوتر زينترا واي فاي 6", "networking", 249900, ["أسود"], [],
     [("السرعة", "AX3000"), ("التغطية", "شقة لحد 150 متر"), ("التوافق", "بيشتغل مع راوتر الشركة (WE، فودافون، أورنج، اتصالات) كأكسس بوينت"), W1Y], "", 279900, ["راوتر", "نت"], "زينترا"),
    ("nw-ext", "مقوي واي فاي زينترا", "networking", 89900, ["أبيض"], [],
     [("السرعة", "AC1200"), ("التركيب", "في الفيشة، تطبيق سهل"), W1Y], "", None, ["مقوي", "نت"], "زينترا"),
    ("gm-pad", "دراع ألعاب لاسلكي زينترا", "gaming-accessories", 119900, ["أسود", "أبيض", "أحمر"], [],
     [("التوافق", "كمبيوتر وأندرويد وتابلت (مش بلايستيشن)"), ("الاتصال", "بلوتوث ودونجل"), ("البطارية", "20 ساعة"), W1Y], "", None, ["دراع", "جيمنج"], "زينترا"),
    ("bg-sleeve", "جراب لاب توب زينترا", "laptop-bags", 34900, ["رمادي", "أسود"], [("المقاس", [("13 - 14 بوصة", 0), ("15.6 بوصة", 5000)])],
     [("الخامة", "قماش مقاوم للمية مع حشو"), ("الجيب", "جيب أمامي للشاحن")], "", None, ["شنطة", "لاب توب"], "زينترا"),
    ("bg-backpack", "شنطة ضهر لاب توب زينترا ضد السرقة", "laptop-bags", 99900, ["أسود", "رمادي"], [],
     [("المقاس", "لاب توب لحد 15.6 بوصة"), ("مميزات", "سوستة مخفية ومدخل USB للشحن"), ("الخامة", "مقاوم للمية")], "BEST_SELLER", None, ["شنطة", "لاب توب"], "زينترا"),
    ("hd-car", "حامل موبايل للعربية مغناطيس", "phone-holders", 24900, ["أسود"], [],
     [("التركيب", "على فتحة التكييف"), ("التوافق", "أي موبايل بالحلقة المعدن اللي معاه")], "", None, ["حامل", "عربية"], "آرمر"),
    ("hd-desk", "ستاند موبايل وتابلت للمكتب", "phone-holders", 19900, ["فضي", "أسود"], [],
     [("التوافق", "موبايل وتابلت لحد 13 بوصة"), ("التعديل", "زاوية وارتفاع")], "", None, ["ستاند"], "آرمر"),
]

ELECTRONICS_PROFILE = {
    "storeName": "تك زون",
    "shortDescription": "موبايلات، لاب توب، تلفزيونات وإكسسوارات بضمان · توصيل لكل محافظات مصر والدفع عند الاستلام",
    "about": "تك زون محل إلكترونيات في المهندسين من ٢٠١٥. كل الأجهزة جديدة بضمان، وفي قسم أوبن بوكس بسعر أقل (العلبة مفتوحة والجهاز سليم). بنساعدك تختار الجهاز المناسب لشغلك أو مذاكرتك أو ألعابك.",
    "announcement": "⚡ كود TECH5 خصم ٥٪ على الطلبات من ٣٠٠٠ جنيه · الشحن مجاني من ٥٠٠٠ جنيه · مفيش تقسيط حالياً",
    "shippingPolicy": "بنشحن لكل محافظات مصر. القاهرة والجيزة ٦٠ جنيه في يوم أو يومين. الإسكندرية والدلتا ٨٠ جنيه في ٢ - ٣ أيام. القناة والصعيد وسيناء والبحر الأحمر ١٠٠ جنيه في ٣ - ٥ أيام. الشحن مجاني للطلبات من ٥٠٠٠ جنيه. التلفزيونات من 50 بوصة بتوصل بعربية خاصة في القاهرة والجيزة بس، والتركيب على الحيطة اختيار بيتدفع مع التلفزيون. تقدر تعاين الجهاز قبل ما تدفع. الدفع كاش عند الاستلام بس، ومفيش تقسيط.",
    "returnPolicy": "الاسترجاع خلال ١٤ يوم من الاستلام لو الجهاز في علبته متفتحش. لو اتفتح بيتبدل بس لو فيه عيب صناعة. السماعات (إيربودز ورأس) مش بترجع بعد فتحها لأسباب صحية. الضمان سنة على الأجهزة و٦ شهور على الإكسسوارات والأوبن بوكس، والضمان بيشمل عيوب الصناعة مش الكسر أو المية. مفيش استبدال جهاز قديم (Trade-in).",
    "supportPhone": "01001234567", "whatsapp": "01001234567", "addressText": "٢٢ شارع شهاب، المهندسين، الجيزة",
    "mapsUrl": "https://maps.google.com/?q=Shehab+Street+Mohandessin",
    "returnWindowDays": 14, "minOrderMinor": 0, "vatIncluded": True, "vatPercent": 14,
}

ELECTRONICS_ZONES = [
    ("القاهرة والجيزة", ["CAI", "GIZ", "QLY"], 6000, 1, 2),
    ("الإسكندرية والدلتا", ["ALX", "BHR", "DKH", "GHR", "KFS", "MNF", "SHR", "DMT"], 8000, 2, 3),
    ("القناة والصعيد وسيناء", ["ISM", "PSD", "SUZ", "NSN", "SSN", "RSS", "FYM", "BNS", "MNY", "AST", "SHG", "QNA", "LXR", "ASN", "WAD", "MTR"], 10000, 3, 5),
]
