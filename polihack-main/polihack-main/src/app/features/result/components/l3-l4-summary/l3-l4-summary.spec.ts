import { ComponentFixture, TestBed } from '@angular/core/testing';

import { L3L4Summary } from './l3-l4-summary';

describe('L3L4Summary', () => {
  let component: L3L4Summary;
  let fixture: ComponentFixture<L3L4Summary>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [L3L4Summary],
    }).compileComponents();

    fixture = TestBed.createComponent(L3L4Summary);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
