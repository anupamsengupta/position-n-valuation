import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { CategoryBadgeStrip, type CategoryDefinition } from './CategoryBadgeStrip';

const CATEGORIES: CategoryDefinition[] = [
  { key: 'AUCTION', label: 'Auction', count: 3 },
  { key: 'SETTLEMENT', label: 'Settlement', count: 2 },
  { key: 'NOMINATION', label: 'Nomination', count: 0 },
];

describe('CategoryBadgeStrip', () => {
  it('prepends an "All" badge with total count', () => {
    render(
      <CategoryBadgeStrip
        categories={CATEGORIES}
        activeCategory={null}
        onCategoryClick={() => {}}
      />,
    );
    const allButton = screen.getByRole('button', { name: /All/i });
    expect(allButton).toBeInTheDocument();
    // Total count = 3 + 2 + 0 = 5
    expect(allButton.textContent).toContain('5');
  });

  it('renders all category badges with counts', () => {
    render(
      <CategoryBadgeStrip
        categories={CATEGORIES}
        activeCategory={null}
        onCategoryClick={() => {}}
      />,
    );
    // 4 buttons total: All + 3 categories
    const buttons = screen.getAllByRole('button');
    expect(buttons).toHaveLength(4);
    expect(screen.getByRole('button', { name: /Auction/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Settlement/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Nomination/i })).toBeInTheDocument();
  });

  it('marks the active category as aria-pressed="true"', () => {
    render(
      <CategoryBadgeStrip
        categories={CATEGORIES}
        activeCategory="AUCTION"
        onCategoryClick={() => {}}
      />,
    );
    const auctionBtn = screen.getByRole('button', { name: /Auction/i });
    expect(auctionBtn).toHaveAttribute('aria-pressed', 'true');

    const allBtn = screen.getByRole('button', { name: /All/i });
    expect(allBtn).toHaveAttribute('aria-pressed', 'false');
  });

  it('marks "All" as active when activeCategory is null', () => {
    render(
      <CategoryBadgeStrip
        categories={CATEGORIES}
        activeCategory={null}
        onCategoryClick={() => {}}
      />,
    );
    const allBtn = screen.getByRole('button', { name: /All/i });
    expect(allBtn).toHaveAttribute('aria-pressed', 'true');
  });

  it('applies opacity to zero-count inactive badges', () => {
    render(
      <CategoryBadgeStrip
        categories={CATEGORIES}
        activeCategory={null}
        onCategoryClick={() => {}}
      />,
    );
    const nominationBtn = screen.getByRole('button', { name: /Nomination/i });
    expect(nominationBtn.className).toContain('opacity-50');
  });

  it('does not apply opacity to zero-count active badges', () => {
    render(
      <CategoryBadgeStrip
        categories={CATEGORIES}
        activeCategory="NOMINATION"
        onCategoryClick={() => {}}
      />,
    );
    const nominationBtn = screen.getByRole('button', { name: /Nomination/i });
    expect(nominationBtn.className).not.toContain('opacity-50');
  });

  it('calls onCategoryClick with the category key on click', () => {
    const handleClick = vi.fn();
    render(
      <CategoryBadgeStrip
        categories={CATEGORIES}
        activeCategory={null}
        onCategoryClick={handleClick}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: /Auction/i }));
    expect(handleClick).toHaveBeenCalledWith('AUCTION');
  });

  it('calls onCategoryClick with null when "All" is clicked', () => {
    const handleClick = vi.fn();
    render(
      <CategoryBadgeStrip
        categories={CATEGORIES}
        activeCategory="AUCTION"
        onCategoryClick={handleClick}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: /All/i }));
    expect(handleClick).toHaveBeenCalledWith(null);
  });

  it('supports keyboard arrow navigation (roving tabindex)', () => {
    render(
      <CategoryBadgeStrip
        categories={CATEGORIES}
        activeCategory={null}
        onCategoryClick={() => {}}
      />,
    );
    const buttons = screen.getAllByRole('button');
    // Active button (All) has tabIndex 0, others have -1
    expect(buttons[0]).toHaveAttribute('tabindex', '0');
    expect(buttons[1]).toHaveAttribute('tabindex', '-1');

    // Focus the first button and press ArrowRight
    buttons[0]?.focus();
    fireEvent.keyDown(buttons[0]!, { key: 'ArrowRight' });
    expect(document.activeElement).toBe(buttons[1]);
  });

  it('wraps keyboard navigation from last to first', () => {
    render(
      <CategoryBadgeStrip
        categories={CATEGORIES}
        activeCategory="NOMINATION"
        onCategoryClick={() => {}}
      />,
    );
    const buttons = screen.getAllByRole('button');
    // Last button (Nomination, index 3)
    buttons[3]?.focus();
    fireEvent.keyDown(buttons[3]!, { key: 'ArrowRight' });
    expect(document.activeElement).toBe(buttons[0]);
  });

  it('has role="toolbar"', () => {
    render(
      <CategoryBadgeStrip
        categories={CATEGORIES}
        activeCategory={null}
        onCategoryClick={() => {}}
      />,
    );
    expect(screen.getByRole('toolbar')).toBeInTheDocument();
  });
});
