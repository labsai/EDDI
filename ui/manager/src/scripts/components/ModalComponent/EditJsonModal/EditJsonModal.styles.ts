import { CSSProperties } from 'react';
import {
  BLUE_COLOR2,
  DARK_BLUE_COLOR,
  GREY_COLOR,
  LIGHT_GREY_COLOR2,
  LIGHT_GREY_COLOR3,
  SMALL_FONT2,
} from '../../../../styles/DefaultStylingProperties';

const editStyles: CSSProperties = {
  tabs: {
    display: 'flex',
    marginTop: '30px',
  },
  tab: {
    ':hover': {
      backgroundColor: LIGHT_GREY_COLOR3,
      borderBottom: `3px solid ${GREY_COLOR}`,
    },
    borderBottom: `3px solid ${BLUE_COLOR2}`,
    color: DARK_BLUE_COLOR,
    cursor: 'pointer',
    display: 'inline-block',
    fontSize: SMALL_FONT2,
    height: '39px',
    textAlign: 'center',
    flex: '1',
    lineHeight: '42px',
  },
  tabDisabled: {
    borderBottom: `3px solid ${LIGHT_GREY_COLOR2}`,
    color: GREY_COLOR,
  },
  schemaForm: {
    paddingTop: '20px',
  },
};

export default editStyles;
