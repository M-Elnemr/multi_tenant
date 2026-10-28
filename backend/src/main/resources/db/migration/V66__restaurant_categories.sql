-- Restaurant and take-away categories under «أكل جاهز» (ready-food, 150), so a restaurant can sort its menu the way customers ask for it.
INSERT INTO commerce.taxonomy (id, parent_id, level, slug, name_ar, name_en, path, icon, size_scales, applies_audience, sort_order, is_hidden) VALUES
(401, 150, 2, 'shawarma', 'شاورما', 'Shawarma', '/150/401/', NULL, '', FALSE, 156, FALSE),
(402, 150, 2, 'fried-chicken', 'فراخ مقرمشة', 'Fried Chicken', '/150/402/', NULL, '', FALSE, 157, FALSE),
(403, 150, 2, 'sandwiches-burgers', 'ساندويتشات وبرجر', 'Sandwiches & Burgers', '/150/403/', NULL, '', FALSE, 158, FALSE),
(404, 150, 2, 'grills', 'مشويات', 'Grills', '/150/404/', NULL, '', FALSE, 159, FALSE),
(405, 150, 2, 'meals-combos', 'وجبات وكومبو', 'Meals & Combos', '/150/405/', NULL, '', FALSE, 160, FALSE),
(406, 150, 2, 'appetizers-sides', 'مقبلات وأطباق جانبية', 'Appetizers & Sides', '/150/406/', NULL, '', FALSE, 161, FALSE),
(407, 150, 2, 'soft-drinks', 'مشروبات', 'Drinks', '/150/407/', NULL, '', FALSE, 162, FALSE),
(408, 150, 2, 'restaurant-desserts', 'حلو المطعم', 'Restaurant Desserts', '/150/408/', NULL, '', FALSE, 163, FALSE)
ON CONFLICT (id) DO NOTHING;
UPDATE commerce.taxonomy SET search_text = commerce.norm_ar(name_ar || ' ' || name_en) WHERE id BETWEEN 401 AND 408;
