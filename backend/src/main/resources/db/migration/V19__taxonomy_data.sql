-- Move existing products onto the standard categories and mark their colour/size options as standard where the words match.
-- Old per-shop categories (commerce.categories, products.category_id) stay in place but are no longer used; owners can pick a proper category any time.

-- 1. category: exact name match first, then "contains" match (e.g. قمصان -> قمصان وتيشيرتات), otherwise the hidden "other" bucket.
UPDATE commerce.products p SET taxonomy_id = coalesce(
    (SELECT t.id FROM commerce.categories c JOIN commerce.taxonomy t ON t.level >= 2 AND NOT t.is_hidden
       AND (commerce.norm_ar(t.name_ar) = commerce.norm_ar(c.name) OR lower(t.name_en) = lower(c.name)) WHERE c.id = p.category_id ORDER BY t.level DESC LIMIT 1),
    (SELECT t.id FROM commerce.categories c JOIN commerce.taxonomy t ON t.level >= 2 AND NOT t.is_hidden
       AND length(commerce.norm_ar(c.name)) >= 3 AND commerce.norm_ar(t.name_ar) LIKE '%' || regexp_replace(commerce.norm_ar(c.name), '^ال', '') || '%'
       WHERE c.id = p.category_id ORDER BY t.level DESC, length(t.name_ar) LIMIT 1),
    (SELECT id FROM commerce.taxonomy WHERE slug = 'other'));
UPDATE commerce.products SET taxonomy_id = (SELECT id FROM commerce.taxonomy WHERE slug = 'other') WHERE taxonomy_id IS NULL;

-- 2. standard colour / size options
UPDATE commerce.product_options SET attribute_code = 'COLOR' WHERE lower(trim(name)) IN ('اللون', 'لون', 'color', 'colour');
UPDATE commerce.product_options SET attribute_code = 'SIZE' WHERE lower(trim(name)) IN ('المقاس', 'مقاس', 'size');

UPDATE commerce.product_option_values v SET value_code = a.code
FROM commerce.product_options o, commerce.attribute_values a
WHERE v.option_id = o.id AND o.attribute_code = 'COLOR' AND a.attr = 'COLOR'
  AND (commerce.norm_ar(a.name_ar) = commerce.norm_ar(v.value) OR lower(a.name_en) = lower(v.value));

-- a size option gets a scale when every one of its values is a value of that scale
UPDATE commerce.product_options o SET size_scale = s.scale
FROM (SELECT o2.id AS option_id, a.scale
        FROM commerce.product_options o2
        JOIN commerce.product_option_values v ON v.option_id = o2.id
        JOIN commerce.attribute_values a ON a.attr = 'SIZE' AND (commerce.norm_ar(a.name_ar) = commerce.norm_ar(v.value) OR lower(a.name_en) = lower(v.value))
       WHERE o2.attribute_code = 'SIZE'
       GROUP BY o2.id, a.scale
      HAVING count(DISTINCT v.id) = (SELECT count(*) FROM commerce.product_option_values v2 WHERE v2.option_id = o2.id)) s
WHERE o.id = s.option_id;
UPDATE commerce.product_option_values v SET value_code = a.code
FROM commerce.product_options o, commerce.attribute_values a
WHERE v.option_id = o.id AND o.attribute_code = 'SIZE' AND o.size_scale <> '' AND a.attr = 'SIZE' AND a.scale = o.size_scale
  AND (commerce.norm_ar(a.name_ar) = commerce.norm_ar(v.value) OR lower(a.name_en) = lower(v.value));
-- a size option that did not match one scale cleanly stays a custom option
UPDATE commerce.product_options SET attribute_code = '' WHERE attribute_code = 'SIZE' AND size_scale = '';
