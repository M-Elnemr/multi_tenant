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
