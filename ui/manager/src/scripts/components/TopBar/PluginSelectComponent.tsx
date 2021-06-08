import FormControl from '@material-ui/core/FormControl';
import MenuItem from '@material-ui/core/MenuItem';
import OutlinedInput from '@material-ui/core/OutlinedInput';
import Select from '@material-ui/core/Select';
import { makeStyles } from '@material-ui/core/styles';
import ArrowDropDownIcon from '@material-ui/icons/ArrowDropDown';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { historyPush } from '../../history';
import { pageEnum } from '../pages/pageEnum';

const useStyles = makeStyles({
  selectContainer: {
    width: 170,
    height: 39,
    borderBottom: '3px solid #e0e5ee',

    '&:hover': {
      borderBottom: '3px solid #7a849e',
      backgroundColor: '#f6f9fb',
    },
    '& .MuiOutlinedInput-root': {
      fontSize: '1.4rem',
      lineHeight: '2rem',
      height: '100%',
      display: 'flex',
      alignItems: 'flex-end',
      color: '#7a849e',
    },
    '& .MuiSelect-icon': {
      top: 'calc(50% - 8px)',
    },
    '& .MuiOutlinedInput-root .MuiOutlinedInput-notchedOutline': {
      borderColor: 'transparent',
    },
  },
  active: {
    '& .MuiOutlinedInput-root': {
      color: '#16325c',
    },
    borderBottom: '3px solid #4a90e2',
  },
  input: {
    '& .MuiOutlinedInput-inputMarginDense': {
      paddingTop: 5,
      paddingBottom: 5,
      backgroundColor: 'transparent',
    },
  },
  select: {
    '& li': {
      fontSize: '1.4rem',
    },
    '& li:hover': {
      backgroundColor: '#DAEAFF',
    },
    '& .Mui-selected': {
      backgroundColor: '#2684FF',
    },
    '& .Mui-disabled': {
      backgroundColor: 'transparent',
    },
    '& .Mui-selected:hover': {
      backgroundColor: '#2684FF',
    },
  },
  arrowDropdown: {
    marginTop: 'auto',
    marginBottom: 'auto',
  },
});

const pluginResourceOptions = [
  'Regular dictionaries',
  'Behavior rules',
  'Output sets',
  'HTTP calls',
  'Git calls',
  'Properties',
];

interface IProps {
  page: pageEnum;
}

interface IOption {
  value: number;
  label: string;
}

const PluginSelectComponent = ({ page }: IProps) => {
  const classes = useStyles();
  const [selectedOption, setSelectedOption] = React.useState<IOption>({
    label: 'Resources',
    value: -1,
  });

  const isPluginPage = (): boolean => {
    return [
      pageEnum.dictionary,
      pageEnum.behavior,
      pageEnum.output,
      pageEnum.httpCalls,
      pageEnum.gitCalls,
      pageEnum.property,
    ].includes(page);
  };

  const setOption = () => {
    if (isPluginPage()) {
      setSelectedOption({ label: pluginResourceOptions[page], value: page });
    } else {
      setSelectedOption({ label: 'Resources', value: -1 });
    }
  };

  React.useEffect(() => {
    setOption();
  }, [page]);

  const handleSelect = (
    event: React.ChangeEvent<{
      value: number;
    }>,
  ) => {
    const value = event?.target?.value;
    if (typeof value === 'number') {
      const selectedOption = pluginResourceOptions[value];
      if (!selectedOption) {
        return;
      }
      setSelectedOption({ label: selectedOption, value });
      historyPush('/resources', [`type=${pageEnum[value]}`]);
    }
  };

  return (
    <FormControl
      size="small"
      className={`${classes.selectContainer} ${
        isPluginPage() ? classes.active : undefined
      }`}>
      <Select
        value={selectedOption.value}
        onChange={handleSelect}
        IconComponent={() => (
          <ArrowDropDownIcon
            className={classes.arrowDropdown}
            fontSize={'large'}
          />
        )}
        input={<OutlinedInput className={classes.input} />}
        MenuProps={{ classes: { paper: classes.select } }}>
        <MenuItem key={'Resources'} value={-1} disabled>
          {'Resources'}
        </MenuItem>
        {pluginResourceOptions.map((p, i) => {
          return (
            <MenuItem key={p + i} value={i}>
              {p}
            </MenuItem>
          );
        })}
      </Select>
    </FormControl>
  );
};

const ComposedPluginSelectComponent: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('PluginSelectComponent'),
)(PluginSelectComponent);

export default ComposedPluginSelectComponent;
