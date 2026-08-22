INSERT INTO products (name, description, price, stock_quantity, category)
SELECT 'Wireless Mouse', 'Ergonomic 2.4GHz wireless mouse', 19.99, 150, 'Electronics'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name = 'Wireless Mouse');

INSERT INTO products (name, description, price, stock_quantity, category)
SELECT 'Mechanical Keyboard', 'RGB backlit mechanical keyboard', 59.99, 80, 'Electronics'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name = 'Mechanical Keyboard');

INSERT INTO products (name, description, price, stock_quantity, category)
SELECT 'Cotton T-Shirt', '100% cotton crew neck t-shirt', 12.50, 300, 'Apparel'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name = 'Cotton T-Shirt');
